package com.ronin.phoneshm.feature.analysis

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.ronin.phoneshm.core.database.PhoneShmDatabase
import androidx.lifecycle.viewModelScope
import com.ronin.phoneshm.core.baseline.BaselineComparisonResult
import com.ronin.phoneshm.core.baseline.BaselineManagerEngine
import com.ronin.phoneshm.core.baseline.DefaultBaselineManagerEngine
import com.ronin.phoneshm.core.dsp.DspEngine
import com.ronin.phoneshm.core.dsp.MultiAxisSpectrumResult
import com.ronin.phoneshm.core.dsp.WelchPsdEngine
import com.ronin.phoneshm.core.dsp.WelchPsdParameters
import com.ronin.phoneshm.core.modal.DefaultModalAnalyzer
import com.ronin.phoneshm.core.modal.ModalAnalysisResult
import com.ronin.phoneshm.core.modal.ModalAnalyzer
import com.ronin.phoneshm.core.modal.ExcitationSufficiency
import com.ronin.phoneshm.core.physics.DefaultPhysicsRulesEngine
import com.ronin.phoneshm.core.physics.FrequencyClassification
import com.ronin.phoneshm.core.physics.PhysicsRulesEngine
import com.ronin.phoneshm.core.physics.PlausibilityClassificationResult
import com.ronin.phoneshm.core.sensor.AccelerationSample
import com.ronin.phoneshm.core.storage.DefaultRawSampleStorageEngine
import com.ronin.phoneshm.core.storage.RawSampleStorageEngine
import com.ronin.phoneshm.core.device.DeviceCapabilityReport
import com.ronin.phoneshm.core.sensor.MeasurementSessionMetadata
import com.ronin.phoneshm.core.quality.MeasurementQualityReport
import com.ronin.phoneshm.core.quality.QualityScoreEngine
import com.ronin.phoneshm.core.quality.DefaultQualityScoreEngine
import java.io.File
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AnalysisUiState(
    val isAnalyzing: Boolean = false,
    val fundamentalFrequencyHz: Double = 0.0,
    val dominantAxis: String = "MAGNITUDE",
    val qualityScorePct: Int = 98,
    val qualityReport: MeasurementQualityReport? = null,
    val baselineShiftPct: Double = 0.0,
    val baselineComparison: BaselineComparisonResult? = null,
    val classificationLabel: String = "GLOBAL_MODE",
    val modalResult: ModalAnalysisResult? = null,
    val spectrum: com.ronin.phoneshm.core.dsp.MultiAxisSpectrumResult? = null,
    val sessionMeta: com.ronin.phoneshm.core.sensor.MeasurementSessionMetadata? = null,
    val deviceReport: com.ronin.phoneshm.core.device.DeviceCapabilityReport? = null,
    val buildingType: String = "RESIDENTIAL_CONCRETE",
    val floors: Int = 3,
    val buildingHash: String = "",
    val analyzedFilePath: String? = null,
    val errorMessage: String? = null,
    val consecutiveFailureCount: Int = 0,
    val isWeakSignalFailure: Boolean = false,
    val measurementProfileId: String = "building_profile_active"
)


/**
 * AnalysisViewModel drives modal frequency display, multi-axis Welch PSD rendering,
 * domain physics plausibility classification, and adaptive persistence tracking.
 */
class AnalysisViewModel(application: Application) : AndroidViewModel(application) {
    private val baseDir: File = application.filesDir ?: File(System.getProperty("java.io.tmpdir"), "phoneshm_data")

    private val dspEngine: DspEngine = WelchPsdEngine()
    private val physicsEngine: PhysicsRulesEngine = DefaultPhysicsRulesEngine()
    private val modalAnalyzer: ModalAnalyzer = DefaultModalAnalyzer()
    private val qualityScoreEngine: QualityScoreEngine = DefaultQualityScoreEngine()
    private val storageEngine: RawSampleStorageEngine = DefaultRawSampleStorageEngine(
        File(baseDir, "raw_sessions")
    )
    private val baselineDao by lazy { PhoneShmDatabase.getDatabase(application).baselineDao() }
    private val baselineEngine: BaselineManagerEngine by lazy {
        DefaultBaselineManagerEngine(
            baselineDao,
            File(baseDir, "baseline_data")
        )
    }
    
    private val _uiState = MutableStateFlow(AnalysisUiState())
    val uiState: StateFlow<AnalysisUiState> = _uiState.asStateFlow()

    fun analyzeSessionFileOrDemo(
        filePath: String?,
        buildingType: String = "RESIDENTIAL_CONCRETE",
        floors: Int = 3,
        buildingHash: String = java.util.UUID.randomUUID().toString()
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isAnalyzing = true,
                errorMessage = null,
                buildingType = buildingType,
                floors = floors,
                buildingHash = buildingHash,
                analyzedFilePath = filePath
            )

            try {
                var sessionMeta: MeasurementSessionMetadata? = null
                var deviceReport: DeviceCapabilityReport? = null

                val samples = withContext(Dispatchers.IO) {
                    if (filePath != null && File(filePath).exists()) {
                        val file = File(filePath)
                        
                        // Attempt to load sidecar JSON metadata (Task 1 & 2)
                        val sessionId = file.nameWithoutExtension
                        val metaFile = File(file.parentFile, "$sessionId.meta.json")
                        if (metaFile.exists()) {
                            try {
                                val decoded = com.ronin.phoneshm.core.sensor.SessionMetadataJsonCodec.decode(metaFile.readText())
                                if (decoded != null) {
                                    sessionMeta = decoded.first
                                    deviceReport = decoded.second
                                } else {
                                    android.util.Log.e("Analysis", "Failed to parse sidecar meta file $metaFile: decode returned null")
                                    sessionMeta = null
                                    deviceReport = null
                                }
                            } catch (e: Exception) {
                                // Task 1c: Do not crash on malformed/partial JSON; treat as metadata missing.
                                android.util.Log.e("Analysis", "Failed to read sidecar meta file $metaFile: ${e.message}")
                                sessionMeta = null
                                deviceReport = null
                            }
                        }

                        val data = storageEngine.readSamplesFromFile(file)
                        if (data.sampleCount > 100) {
                            List(data.sampleCount) { i ->
                                AccelerationSample(data.timestampsNs[i], data.x[i], data.y[i], data.z[i])
                            }
                        } else {
                            generateSyntheticStructuralSamples(buildingType, floors)
                        }
                    } else {
                        generateSyntheticStructuralSamples(buildingType, floors)
                    }
                }

                // Recommendation #3: On-device SNR/quality gate
                val gravityRemoved = dspEngine.removeGravityAndDetrend(samples)
                val rms = kotlin.math.sqrt(gravityRemoved.gravityFreeSamples.map { (it.x * it.x + it.y * it.y + it.z * it.z).toDouble() }.average())
                val rmsMg = rms * 1000.0 / 9.80665
                val noiseFloor = (sessionMeta?.sessionNoiseFloorMg ?: deviceReport?.estimatedNoiseFloorMg)?.toDouble() ?: 0.45
                var snrWarning: String? = null
                val isSynthetic = (sessionMeta == null)
                val isAmbientMode = sessionMeta?.measurementProfileId == "ambient_baseline_continuous"
                val profileId = sessionMeta?.measurementProfileId ?: "building_profile_active"
                val effectiveNoiseThreshold = if (isAmbientMode) noiseFloor * 0.3 else noiseFloor
                if (rmsMg < effectiveNoiseThreshold && !isSynthetic) {
                    snrWarning = if (isAmbientMode) {
                        "Ambient signal extremely weak — even with extended averaging, structural frequencies may not be recoverable. Try recording when there is more environmental activity (traffic, wind, footfall)."
                    } else {
                        "Signal too weak (RMS < %.2f mg) — building may not have been excited, retry?".format(noiseFloor)
                    }
                }

                val sampleRateHz = 100.0f

                // Profile-dependent Welch PSD parameters
                val mainFftSize: Int
                val windowSize: Int
                val stepSize: Int
                val slidingFftSize: Int

                if (isAmbientMode) {
                    // Ambient mode: larger FFT for better frequency resolution, longer sliding windows
                    mainFftSize = minOf(4096, Integer.highestOneBit(samples.size))
                    windowSize = 6000   // 60s segments
                    stepSize = 3000     // 50% overlap
                    slidingFftSize = 2048
                } else {
                    // Impulse mode: standard parameters for 66s capture
                    mainFftSize = minOf(2048, Integer.highestOneBit(samples.size))
                    windowSize = 512    // 5.12s windows
                    stepSize = 256      // 2.56s step
                    slidingFftSize = 256
                }

                val mainParams = com.ronin.phoneshm.core.dsp.WelchPsdParameters(fftSize = mainFftSize)
                val mainSpectrum = dspEngine.calculateMultiAxisWelchPsd(
                    samples, 
                    sampleRateHz, 
                    mainParams, 
                    com.ronin.phoneshm.core.storage.RemoteConfigManager.ambientSnrThresholdDb
                )

                val slidingParams = WelchPsdParameters(fftSize = slidingFftSize)
                val slidingSpectra = mutableListOf<MultiAxisSpectrumResult>()
                if (samples.size >= windowSize) {
                    var i = 0
                    while (i + windowSize <= samples.size) {
                        val winSamples = samples.subList(i, i + windowSize)
                        slidingSpectra.add(dspEngine.calculateMultiAxisWelchPsd(
                            winSamples, 
                            sampleRateHz, 
                            slidingParams,
                            com.ronin.phoneshm.core.storage.RemoteConfigManager.ambientSnrThresholdDb
                        ))
                        i += stepSize
                    }
                }

                // 3. Run ModalAnalyzer across spectra with adaptive persistence tracking
                //    Physics plausibility is evaluated inline on the final selected modal frequency
                val modalRes = modalAnalyzer.analyzeMultiAxisSpectrum(
                    spectrum = mainSpectrum,
                    slidingWindowSpectra = slidingSpectra,
                    evaluatePhysics = { f0Hz, prominence ->
                        physicsEngine.classifyFrequency(
                            f0Hz = f0Hz,
                            prominence = prominence.toFloat(),
                            buildingType = buildingType,
                            floors = floors
                        )
                    }
                )

                // 4. Verify impulse-mode quality (TASK A) and sampling continuity (TASK 3)
                val peakToRmsThreshold = com.ronin.phoneshm.core.storage.RemoteConfigManager.peakToRmsThreshold
                android.util.Log.d("RemoteConfig", "Fetched PEAK_TO_RMS_THRESHOLD = $peakToRmsThreshold")
                val impulseQuality = if (!isAmbientMode) {
                    dspEngine.verifyImpulseQuality(
                        samples, 
                        sampleRateHz,
                        peakToRmsThreshold,
                        com.ronin.phoneshm.core.storage.RemoteConfigManager.spectralSanityThreshold
                    )
                } else null

                val samplingContinuity = dspEngine.verifySamplingContinuity(
                    samples = samples,
                    maxAllowedMissingRatio = com.ronin.phoneshm.core.storage.RemoteConfigManager.gapMissingTimeRatioThreshold
                )

                // 5. Compare with baseline and update
                
                val effectiveConfidence = if (modalRes.excitationSufficiency == ExcitationSufficiency.INSUFFICIENT ||
                    (impulseQuality != null && !impulseQuality.isImpulseValid) ||
                    !samplingContinuity.isContinuityPassed) {
                    0.0
                } else {
                    modalRes.confidence
                }

                val baselineResult = baselineEngine.compareWithBaseline(
                    buildingHash = buildingHash,
                    measurementProfileId = profileId,
                    currentF0Hz = modalRes.fundamentalFrequencyHz,
                    confidence = effectiveConfidence
                )

                // Task 2: Compute QualityScore
                val finalQualityScorePct: Int
                val qualityReportRes: MeasurementQualityReport?

                if (sessionMeta != null && deviceReport != null) {
                    qualityReportRes = qualityScoreEngine.calculateQualityScore(
                        session = sessionMeta!!,
                        device = deviceReport!!,
                        audio = null, // Audio context: not wired, deferred pending field-data justification
                        modal = modalRes
                    )
                    finalQualityScorePct = qualityReportRes.totalScorePct
                } else {
                    // Task 3: Fallback path for sessions without metadata
                    // Explicit choice: Exclude from baseline updates entirely.
                    // We set quality to 49 (which strictly fails the 50% baseline gate in DefaultBaselineManagerEngine)
                    // so that synthetic data or old recordings never pollute the real baseline Welford statistics.
                    qualityReportRes = null
                    finalQualityScorePct = 49
                }

                // Auto-update baseline with this session (using real qualityScorePct, not hardcoded 80)
                baselineEngine.updateBaselineWithSession(
                    buildingHash = buildingHash,
                    measurementProfileId = profileId,
                    currentF0Hz = modalRes.fundamentalFrequencyHz,
                    qualityScorePct = finalQualityScorePct
                )

                // Update sidecar metadata with quality results so Session History can display it
                if (sessionMeta != null && deviceReport != null && filePath != null) {
                    try {
                        val updatedMeta = sessionMeta!!.copy(
                            isImpulseValid = impulseQuality?.isImpulseValid ?: true,
                            isContinuityPassed = samplingContinuity.isContinuityPassed,
                            qualityGatePassed = finalQualityScorePct >= 50
                        )
                        val metaFile = java.io.File(filePath.replace(".bin", ".meta.json"))
                        if (metaFile.exists()) {
                            val jsonString = com.ronin.phoneshm.core.sensor.SessionMetadataJsonCodec.encode(updatedMeta, deviceReport!!)
                            metaFile.writeText(jsonString)
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("Analysis", "Failed to update sidecar meta file: ${e.message}")
                    }
                }

                _uiState.value = _uiState.value.copy(
                    isAnalyzing = false,
                    fundamentalFrequencyHz = modalRes.fundamentalFrequencyHz,
                    dominantAxis = modalRes.dominantAxis,
                    classificationLabel = modalRes.classification.classification.name,
                    modalResult = modalRes,
                    baselineShiftPct = baselineResult.percentageShift,
                    baselineComparison = baselineResult,
                    qualityScorePct = finalQualityScorePct,
                    qualityReport = qualityReportRes,
                    errorMessage = snrWarning,
                    isWeakSignalFailure = snrWarning != null,
                    consecutiveFailureCount = if (snrWarning != null) _uiState.value.consecutiveFailureCount + 1 else 0,
                    buildingType = sessionMeta?.buildingType ?: buildingType,
                    floors = sessionMeta?.floors ?: floors,
                    measurementProfileId = profileId,
                    spectrum = mainSpectrum,
                    sessionMeta = sessionMeta,
                    deviceReport = deviceReport
                )
            } catch (e: Exception) { println("JSON ERROR: " + e.message); e.printStackTrace();
                _uiState.value = _uiState.value.copy(
                    isAnalyzing = false,
                    errorMessage = "Analysis error: ${e.message}"
                )
            }
        }
    }

    private fun generateSyntheticStructuralSamples(buildingType: String, floors: Int): List<AccelerationSample> {
        val count = 4096 // ~40.96 seconds @ 100Hz — enough for robust persistence tracking
        val dt = 0.01 // 10 ms
        val bt = buildingType.lowercase()
        val targetF0 = when {
            bt.contains("steel") -> 12.0 / maxOf(1, floors)
            bt.contains("masonry") || bt.contains("brick") || bt.contains("block") -> 18.0 / maxOf(1, floors)
            bt.contains("timber") || bt.contains("wood") || bt.contains("clt") -> 8.0 / maxOf(1, floors)
            else -> 10.0 / maxOf(1, floors) // Concrete / Composite / Unknown
        }
        val targetF1 = targetF0 * 3.1 // Higher harmonic / local mode
        return List(count) { i ->
            val t = i * dt
            val x = (0.05 * sin(2.0 * Math.PI * targetF0 * t) + 0.01 * sin(2.0 * Math.PI * 50.0 * t)).toFloat()
            val y = (0.08 * sin(2.0 * Math.PI * targetF0 * t) + 0.02 * sin(2.0 * Math.PI * targetF1 * t)).toFloat()
            val z = (9.80665 + 0.03 * sin(2.0 * Math.PI * targetF0 * t)).toFloat()
            AccelerationSample((i * 10_000_000L), x, y, z)
        }
    }

    fun updateResults(f0: Double, axis: String, shift: Double, classification: String) {
        _uiState.value = _uiState.value.copy(
            fundamentalFrequencyHz = f0,
            dominantAxis = axis,
            baselineShiftPct = shift,
            classificationLabel = classification,
            isAnalyzing = false
        )
    }

    fun resetBaseline(buildingHash: String, profileId: String) {
        viewModelScope.launch {
            val previous = baselineEngine.getOrCreateBaseline(buildingHash, profileId)
            if (previous != null) {
                android.util.Log.w("BaselineAudit", "Manual debug reset of baseline for building $buildingHash. Previous: mean=${previous.meanF0Hz}, std=${previous.stdF0Hz}, n=${previous.measurementCount}. Time=${System.currentTimeMillis()}")
            }
            baselineEngine.resetBaseline(buildingHash, profileId)
            if (_uiState.value.buildingHash == buildingHash) {
                _uiState.value = _uiState.value.copy(
                    baselineComparison = null,
                    baselineShiftPct = 0.0
                )
            }
        }
    }
}

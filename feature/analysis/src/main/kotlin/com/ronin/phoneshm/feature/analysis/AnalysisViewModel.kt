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

/**
 * Explicit analysis lifecycle states — prevents misleading default values
 * from being shown before analysis completes. (Remediation Phase 0-A)
 */
enum class AnalysisStatus {
    NOT_STARTED, ANALYZING, VALID, DEMO_RESULT,
    INVALID_TIMING, INSUFFICIENT_EXCITATION, INSUFFICIENT_DATA, INCONCLUSIVE, ERROR
}

data class AnalysisUiState(
    val isAnalyzing: Boolean = false,
    val analysisStatus: AnalysisStatus = AnalysisStatus.NOT_STARTED,
    val fundamentalFrequencyHz: Double = 0.0,
    val dominantAxis: String = "UNKNOWN",
    val qualityScorePct: Int = 0,
    val qualityReport: MeasurementQualityReport? = null,
    val baselineShiftPct: Double = 0.0,
    val baselineComparison: BaselineComparisonResult? = null,
    val classificationLabel: String = "PENDING",
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
    val measurementProfileId: String = "building_profile_active",
    val efddResult: com.ronin.phoneshm.core.dsp.NativeFddResult? = null,
    val rdtSsiResult: com.ronin.phoneshm.core.dsp.RdtSsiResult? = null,
    val estimatedSampleRateHz: Float = 0f,
    val consensusResult: com.ronin.phoneshm.core.modal.ModalConsensusResult? = null
)


/**
 * AnalysisViewModel drives modal frequency display, multi-axis Welch PSD rendering,
 * domain physics plausibility classification, and adaptive persistence tracking.
 */
class AnalysisViewModel(application: Application) : AndroidViewModel(application) {
    private val baseDir: File = application.filesDir ?: File(System.getProperty("java.io.tmpdir"), "phoneshm_data")

    private val dspEngine: DspEngine = WelchPsdEngine()
    private val rdtSsiEngine = com.ronin.phoneshm.core.dsp.DefaultRdtSsiEngine()
    private val physicsEngine: PhysicsRulesEngine = DefaultPhysicsRulesEngine()
    private val modalAnalyzer: ModalAnalyzer = DefaultModalAnalyzer()
    private val qualityScoreEngine: QualityScoreEngine = DefaultQualityScoreEngine()
    private val modalConsensusEngine = com.ronin.phoneshm.core.modal.ModalConsensusEngine()
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
                analysisStatus = AnalysisStatus.ANALYZING,
                errorMessage = null,
                buildingType = buildingType,
                floors = floors,
                buildingHash = buildingHash,
                analyzedFilePath = filePath
            )

            try {
                // 1. Check file existence on IO dispatcher if a path was explicitly provided
                val isDemoMode = (filePath == null)
                if (filePath != null) {
                    val fileExists = withContext(Dispatchers.IO) { File(filePath).exists() }
                    if (!fileExists) {
                        _uiState.value = _uiState.value.copy(
                            isAnalyzing = false,
                            analysisStatus = AnalysisStatus.ERROR,
                            errorMessage = "Session file not found: $filePath"
                        )
                        return@launch
                    }
                }

                // 2. Load samples & metadata on IO (or synthesize demo data on Default)
                var sessionMeta: MeasurementSessionMetadata? = null
                var deviceReport: DeviceCapabilityReport? = null

                val samples = if (filePath != null) {
                    withContext(Dispatchers.IO) {
                        val file = File(filePath)
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
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("Analysis", "Failed to read sidecar meta file $metaFile: ${e.message}")
                            }
                        }

                        val data = storageEngine.readSamplesFromFile(file)
                        if (data.sampleCount > 100) {
                            List(data.sampleCount) { i ->
                                AccelerationSample(data.timestampsNs[i], data.x[i], data.y[i], data.z[i])
                            }
                        } else {
                            emptyList()
                        }
                    }
                } else {
                    withContext(Dispatchers.Default) {
                        generateSyntheticStructuralSamples(buildingType, floors)
                    }
                }

                if (samples.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        isAnalyzing = false,
                        analysisStatus = AnalysisStatus.INSUFFICIENT_DATA,
                        errorMessage = "Insufficient data: too few samples recorded. Need > 100."
                    )
                    return@launch
                }

                // 3. Perform ALL heavy DSP, Linear Algebra, JNI, and Algorithms on Dispatchers.Default
                val computation = withContext(Dispatchers.Default) {
                    val actualBuildingType = sessionMeta?.buildingType ?: buildingType
                    val actualFloors = sessionMeta?.floors ?: floors

                    // Phase 1-A: Use actual sample rate from session metadata or estimate from timestamps
                    val sampleRateHz: Float = sessionMeta?.actualAverageSampleRateHz
                        ?: estimateSampleRateFromTimestamps(samples)
                        ?: 100.0f

                    // Phase 1-B: Uniform Grid Resampling
                    val resampledSamples = dspEngine.resampleToUniformGrid(samples, sampleRateHz)

                    // SNR / Quality gate
                    val gravityRemoved = dspEngine.removeGravityAndDetrend(resampledSamples)
                    val rms = kotlin.math.sqrt(gravityRemoved.gravityFreeSamples.map { (it.x * it.x + it.y * it.y + it.z * it.z).toDouble() }.average())
                    val rmsMg = rms * 1000.0 / 9.80665
                    val noiseFloor = (sessionMeta?.sessionNoiseFloorMg ?: deviceReport?.estimatedNoiseFloorMg)?.toDouble() ?: 0.45
                    var snrWarning: String? = null
                    val isSynthetic = isDemoMode

                    val isAmbientProfileId = sessionMeta?.measurementProfileId == "ambient_baseline_continuous"
                    val isAmbientDuration = (sessionMeta?.targetDurationSeconds ?: 0) >= 60
                    val isAmbientMode = isAmbientProfileId || isAmbientDuration

                    val profileId = sessionMeta?.measurementProfileId ?: "building_profile_active"
                    val effectiveNoiseThreshold = noiseFloor * com.ronin.phoneshm.core.storage.RemoteConfigManager.minRmsMultiplier
                    if (rmsMg < effectiveNoiseThreshold && !isSynthetic) {
                        snrWarning = if (isAmbientMode) {
                            "Ambient signal extremely weak — even with extended averaging, structural frequencies may not be recoverable. Try recording when there is more environmental activity (traffic, wind, footfall)."
                        } else {
                            "Signal too weak (RMS < %.2f mg) — building may not have been excited, retry?".format(noiseFloor)
                        }
                    }

                    // Profile-dependent Welch PSD parameters
                    val mainFftSize: Int
                    val windowSize: Int
                    val stepSize: Int
                    val slidingFftSize: Int

                    if (isAmbientMode) {
                        mainFftSize = minOf(4096, Integer.highestOneBit(resampledSamples.size))
                        windowSize = 6000
                        stepSize = 3000
                        slidingFftSize = 2048
                    } else {
                        mainFftSize = minOf(2048, Integer.highestOneBit(resampledSamples.size))
                        windowSize = 512
                        stepSize = 256
                        slidingFftSize = 256
                    }

                    val mainParams = WelchPsdParameters(fftSize = mainFftSize)
                    val mainSpectrum = dspEngine.calculateMultiAxisWelchPsd(
                        resampledSamples, 
                        sampleRateHz, 
                        mainParams, 
                        com.ronin.phoneshm.core.storage.RemoteConfigManager.ambientSnrThresholdDb
                    )

                    // Run EFDD via JNI natively with zero-boxing primitive arrays
                    val sampleCount = resampledSamples.size
                    val tsArray = LongArray(sampleCount)
                    val xArray = FloatArray(sampleCount)
                    val yArray = FloatArray(sampleCount)
                    val zArray = FloatArray(sampleCount)
                    for (idx in 0 until sampleCount) {
                        val s = resampledSamples[idx]
                        tsArray[idx] = s.timestampNs
                        xArray[idx] = s.x
                        yArray[idx] = s.y
                        zArray[idx] = s.z
                    }

                    val efddResult = try {
                        val res = com.ronin.phoneshm.core.dsp.NativeDspBridge.nativeCalculateFdd(
                            tsArray, xArray, yArray, zArray,
                            sampleRateHz, mainFftSize, 0.5f
                        )
                        android.util.Log.i("Analysis", "EFDD computed: modes=${res.peakFrequencies.size}, freqs=${res.frequencies.size}")
                        res
                    } catch (e: Throwable) {
                        android.util.Log.e("Analysis", "Failed to compute EFDD: ${e.message}", e)
                        null
                    }

                    // Run Multi-Channel RDT-SSI via JNI natively
                    val rdtSsiResult = try {
                        val config = com.ronin.phoneshm.core.physics.PhysicsRulesConfig.loadBundledConfig()
                        val band = config.resolveBand(actualBuildingType)
                        val (minGlobalHz, maxGlobalHz) = band.computeBand(actualFloors)
                        val minHz = maxOf(0.5f, minGlobalHz.toFloat() - 0.5f)
                        val maxHz = minOf(45.0f, maxGlobalHz.toFloat() + 2.0f)
                        
                        rdtSsiEngine.calculateSsi(
                            tsArray, xArray, yArray, zArray,
                            sampleRateHz, minHz, maxHz
                        )
                    } catch (e: Throwable) {
                        android.util.Log.e("Analysis", "Failed to compute RDT-SSI: ${e.message}")
                        null
                    }

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

                    // Run ModalAnalyzer across spectra with adaptive persistence tracking
                    val modalRes = modalAnalyzer.analyzeMultiAxisSpectrum(
                        spectrum = mainSpectrum,
                        slidingWindowSpectra = slidingSpectra,
                        buildingType = actualBuildingType,
                        evaluatePhysics = { f0Hz, prominence ->
                            physicsEngine.classifyFrequency(
                                f0Hz = f0Hz,
                                prominence = prominence.toFloat(),
                                buildingType = actualBuildingType,
                                floors = actualFloors
                            )
                        }
                    )

                    // Verify impulse quality and sampling continuity
                    val peakToRmsThreshold = com.ronin.phoneshm.core.storage.RemoteConfigManager.peakToRmsThreshold
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

                    // Compute QualityScore
                    val finalQualityScorePct: Int
                    val qualityReportRes: MeasurementQualityReport?

                    if (sessionMeta != null && deviceReport != null) {
                        qualityReportRes = qualityScoreEngine.calculateQualityScore(
                            session = sessionMeta!!,
                            device = deviceReport!!,
                            audio = null,
                            modal = modalRes
                        )
                        finalQualityScorePct = qualityReportRes.totalScorePct
                    } else {
                        qualityReportRes = null
                        finalQualityScorePct = 49
                    }

                    // Demo mode sessions must not update real baseline
                    if (!isDemoMode) {
                        baselineEngine.updateBaselineWithSession(
                            buildingHash = buildingHash,
                            measurementProfileId = profileId,
                            currentF0Hz = modalRes.fundamentalFrequencyHz,
                            qualityScorePct = finalQualityScorePct,
                            timeOfDay = sessionMeta?.timeOfDay,
                            temperatureCelsius = sessionMeta?.batteryTemperatureCelsius
                        )
                    }

                    // Phase 3-A: Evaluate Modal Consensus between Welch, EFDD, and SSI
                    val consensusResult = modalConsensusEngine.evaluateConsensus(
                        modalRes = modalRes,
                        efddResult = efddResult,
                        ssiResult = rdtSsiResult
                    )

                    val finalStatus = when {
                        isDemoMode -> AnalysisStatus.DEMO_RESULT
                        snrWarning != null -> AnalysisStatus.INSUFFICIENT_EXCITATION
                        consensusResult.status == com.ronin.phoneshm.core.modal.ConsensusStatus.DISAGREED -> AnalysisStatus.INCONCLUSIVE
                        modalRes.fundamentalFrequencyHz <= 0.0 -> AnalysisStatus.INCONCLUSIVE
                        else -> AnalysisStatus.VALID
                    }

                    AnalysisComputation(
                        actualBuildingType = actualBuildingType,
                        actualFloors = actualFloors,
                        sampleRateHz = sampleRateHz,
                        profileId = profileId,
                        snrWarning = snrWarning,
                        mainSpectrum = mainSpectrum,
                        efddResult = efddResult,
                        rdtSsiResult = rdtSsiResult,
                        modalRes = modalRes,
                        impulseQuality = impulseQuality,
                        samplingContinuity = samplingContinuity,
                        baselineResult = baselineResult,
                        finalQualityScorePct = finalQualityScorePct,
                        qualityReportRes = qualityReportRes,
                        consensusResult = consensusResult,
                        finalStatus = finalStatus
                    )
                }

                // 4. Update sidecar metadata on Dispatchers.IO
                if (sessionMeta != null && deviceReport != null && filePath != null) {
                    withContext(Dispatchers.IO) {
                        try {
                            val updatedMeta = sessionMeta!!.copy(
                                isImpulseValid = computation.impulseQuality?.isImpulseValid ?: true,
                                isContinuityPassed = computation.samplingContinuity.isContinuityPassed,
                                qualityGatePassed = computation.finalQualityScorePct >= 50
                            )
                            val metaFile = File(filePath.replace(".bin", ".meta.json"))
                            if (metaFile.exists()) {
                                val jsonString = com.ronin.phoneshm.core.sensor.SessionMetadataJsonCodec.encode(updatedMeta, deviceReport!!)
                                metaFile.writeText(jsonString)
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("Analysis", "Failed to update sidecar meta file: ${e.message}")
                        }
                        Unit
                    }
                }

                // 5. Update UI State on Main thread
                _uiState.value = _uiState.value.copy(
                    isAnalyzing = false,
                    analysisStatus = computation.finalStatus,
                    fundamentalFrequencyHz = computation.consensusResult.consensusF0 ?: computation.modalRes.fundamentalFrequencyHz,
                    dominantAxis = computation.modalRes.dominantAxis,
                    classificationLabel = computation.modalRes.classification.classification.name,
                    modalResult = computation.modalRes,
                    baselineShiftPct = computation.baselineResult.percentageShift,
                    baselineComparison = computation.baselineResult,
                    qualityScorePct = computation.finalQualityScorePct,
                    qualityReport = computation.qualityReportRes,
                    errorMessage = computation.snrWarning,
                    isWeakSignalFailure = computation.snrWarning != null,
                    consecutiveFailureCount = if (computation.snrWarning != null) _uiState.value.consecutiveFailureCount + 1 else 0,
                    buildingType = computation.actualBuildingType,
                    floors = computation.actualFloors,
                    measurementProfileId = computation.profileId,
                    spectrum = computation.mainSpectrum,
                    sessionMeta = sessionMeta,
                    deviceReport = deviceReport,
                    efddResult = computation.efddResult,
                    rdtSsiResult = computation.rdtSsiResult,
                    estimatedSampleRateHz = computation.sampleRateHz,
                    consensusResult = computation.consensusResult
                )
            } catch (e: Exception) {
                android.util.Log.e("Analysis", "Analysis calculation failed", e)
                _uiState.value = _uiState.value.copy(
                    isAnalyzing = false,
                    analysisStatus = AnalysisStatus.ERROR,
                    errorMessage = "Analysis error: ${e.message ?: "Unknown error"}"
                )
            }
        }
    }

    private data class AnalysisComputation(
        val actualBuildingType: String,
        val actualFloors: Int,
        val sampleRateHz: Float,
        val profileId: String,
        val snrWarning: String?,
        val mainSpectrum: MultiAxisSpectrumResult,
        val efddResult: com.ronin.phoneshm.core.dsp.NativeFddResult?,
        val rdtSsiResult: com.ronin.phoneshm.core.dsp.RdtSsiResult?,
        val modalRes: ModalAnalysisResult,
        val impulseQuality: WelchPsdEngine.ImpulseVerificationResult?,
        val samplingContinuity: WelchPsdEngine.SamplingContinuityResult,
        val baselineResult: BaselineComparisonResult,
        val finalQualityScorePct: Int,
        val qualityReportRes: MeasurementQualityReport?,
        val consensusResult: com.ronin.phoneshm.core.modal.ModalConsensusResult,
        val finalStatus: AnalysisStatus
    )

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

    /**
     * Phase 1-A: Estimate actual sample rate from hardware timestamps.
     * Uses median interval to be robust against outlier gaps/jitter.
     * Returns null if insufficient samples to estimate reliably.
     */
    private fun estimateSampleRateFromTimestamps(samples: List<AccelerationSample>): Float? {
        if (samples.size < 20) return null
        val intervals = (1 until samples.size).map {
            (samples[it].timestampNs - samples[it - 1].timestampNs) / 1_000_000_000.0
        }
        val medianDt = intervals.sorted()[intervals.size / 2]
        return if (medianDt > 0.0) (1.0 / medianDt).toFloat() else null
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

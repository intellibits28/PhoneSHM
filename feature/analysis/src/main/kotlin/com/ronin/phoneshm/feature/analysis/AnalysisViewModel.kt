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
    val buildingType: String = "RESIDENTIAL_CONCRETE",
    val floors: Int = 3,
    val buildingHash: String = "",
    val analyzedFilePath: String? = null,
    val errorMessage: String? = null
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
        buildingHash: String = "demo_building"
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
                                val json = org.json.JSONObject(metaFile.readText())
                                val metaJson = json.getJSONObject("metadata")
                                sessionMeta = MeasurementSessionMetadata(
                                    sessionId = metaJson.getString("sessionId"),
                                    measurementProfileId = metaJson.getString("measurementProfileId"),
                                    deviceCapabilityReportId = metaJson.getString("deviceCapabilityReportId"),
                                    targetDurationSeconds = metaJson.getInt("targetDurationSeconds"),
                                    targetSampleRateHz = metaJson.getInt("targetSampleRateHz"),
                                    actualAverageSampleRateHz = metaJson.getDouble("actualAverageSampleRateHz").toFloat(),
                                    sampleJitterStdMs = metaJson.getDouble("sampleJitterStdMs").toFloat(),
                                    clockDriftPpm = metaJson.getDouble("clockDriftPpm").toFloat(),
                                    rawStorageFileUri = metaJson.getString("rawStorageFileUri")
                                )

                                val devJson = json.getJSONObject("deviceReport")
                                val biasArr = devJson.getJSONArray("accelerometerBias")
                                val bias = FloatArray(biasArr.length()) { i -> biasArr.getDouble(i).toFloat() }
                                deviceReport = DeviceCapabilityReport(
                                    deviceModel = devJson.getString("deviceModel"),
                                    sensorVendor = devJson.getString("sensorVendor"),
                                    maxSupportedSampleRateHz = devJson.getInt("maxSupportedSampleRateHz"),
                                    estimatedNoiseFloorMg = devJson.getDouble("estimatedNoiseFloorMg").toFloat(),
                                    accelerometerBias = bias,
                                    qualityTier = com.ronin.phoneshm.core.device.SensorQualityTier.valueOf(devJson.getString("qualityTier"))
                                )
                            } catch (e: Exception) { println("JSON ERROR: " + e.message); e.printStackTrace();
                                android.util.Log.e("Analysis", "Failed to parse meta file: ${e.message}")
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

                val sampleRateHz = 100.0f

                // Adaptive FFT size: use largest power-of-2 ≤ sample count, capped at 1024
                val mainFftSize = minOf(1024, Integer.highestOneBit(samples.size))
                val mainParams = WelchPsdParameters(fftSize = mainFftSize)

                // 1. Compute multi-axis Welch PSD on full session
                val mainSpectrum = dspEngine.calculateMultiAxisWelchPsd(samples, sampleRateHz, mainParams)

                // 2. Compute sliding window spectra for persistence tracking
                //    Window = 512 samples (5.12s), step = 256 (2.56s), fftSize = 256 for each window
                val windowSize = 512
                val stepSize = 256
                val slidingFftSize = 256
                val slidingParams = WelchPsdParameters(fftSize = slidingFftSize)
                val slidingSpectra = mutableListOf<MultiAxisSpectrumResult>()
                if (samples.size >= windowSize) {
                    var i = 0
                    while (i + windowSize <= samples.size) {
                        val winSamples = samples.subList(i, i + windowSize)
                        slidingSpectra.add(dspEngine.calculateMultiAxisWelchPsd(winSamples, sampleRateHz, slidingParams))
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

                // 5. Compare with baseline and update
                
                val effectiveConfidence = if (modalRes.excitationSufficiency == ExcitationSufficiency.INSUFFICIENT) {
                    0.0
                } else {
                    modalRes.confidence
                }

                val baselineResult = baselineEngine.compareWithBaseline(
                    buildingHash = buildingHash,
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
                        audio = null, // Audio context not yet captured in recording flow
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
                    currentF0Hz = modalRes.fundamentalFrequencyHz,
                    qualityScorePct = finalQualityScorePct
                )

                _uiState.value = _uiState.value.copy(
                    isAnalyzing = false,
                    fundamentalFrequencyHz = modalRes.fundamentalFrequencyHz,
                    dominantAxis = modalRes.dominantAxis,
                    classificationLabel = modalRes.classification.classification.name,
                    modalResult = modalRes,
                    baselineShiftPct = baselineResult.percentageShift,
                    baselineComparison = baselineResult,
                    qualityScorePct = finalQualityScorePct,
                    qualityReport = qualityReportRes
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

    fun resetBaseline(buildingHash: String) {
        viewModelScope.launch {
            val previous = baselineEngine.getOrCreateBaseline(buildingHash)
            if (previous != null) {
                android.util.Log.w("BaselineAudit", "Manual debug reset of baseline for building $buildingHash. Previous: mean=${previous.meanF0Hz}, std=${previous.stdF0Hz}, n=${previous.measurementCount}. Time=${System.currentTimeMillis()}")
            }
            baselineEngine.resetBaseline(buildingHash)
            if (_uiState.value.buildingHash == buildingHash) {
                _uiState.value = _uiState.value.copy(
                    baselineComparison = null,
                    baselineShiftPct = 0.0
                )
            }
        }
    }
}

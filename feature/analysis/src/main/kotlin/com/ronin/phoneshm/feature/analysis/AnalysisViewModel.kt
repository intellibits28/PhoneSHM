package com.ronin.phoneshm.feature.analysis

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ronin.phoneshm.core.dsp.DspEngine
import com.ronin.phoneshm.core.dsp.MultiAxisSpectrumResult
import com.ronin.phoneshm.core.dsp.WelchPsdEngine
import com.ronin.phoneshm.core.dsp.WelchPsdParameters
import com.ronin.phoneshm.core.modal.DefaultModalAnalyzer
import com.ronin.phoneshm.core.modal.ModalAnalysisResult
import com.ronin.phoneshm.core.modal.ModalAnalyzer
import com.ronin.phoneshm.core.physics.DefaultPhysicsRulesEngine
import com.ronin.phoneshm.core.physics.FrequencyClassification
import com.ronin.phoneshm.core.physics.PhysicsRulesEngine
import com.ronin.phoneshm.core.physics.PlausibilityClassificationResult
import com.ronin.phoneshm.core.sensor.AccelerationSample
import com.ronin.phoneshm.core.storage.DefaultRawSampleStorageEngine
import com.ronin.phoneshm.core.storage.RawSampleStorageEngine
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
    val baselineShiftPct: Double = -0.4,
    val classificationLabel: String = "GLOBAL_MODE",
    val modalResult: ModalAnalysisResult? = null,
    val buildingType: String = "RESIDENTIAL_CONCRETE",
    val floors: Int = 3,
    val analyzedFilePath: String? = null,
    val errorMessage: String? = null
)

/**
 * AnalysisViewModel drives modal frequency display, multi-axis Welch PSD rendering,
 * domain physics plausibility classification, and adaptive persistence tracking.
 */
class AnalysisViewModel(
    private val dspEngine: DspEngine = WelchPsdEngine(),
    private val physicsEngine: PhysicsRulesEngine = DefaultPhysicsRulesEngine(),
    private val modalAnalyzer: ModalAnalyzer = DefaultModalAnalyzer(),
    private val storageEngine: RawSampleStorageEngine = DefaultRawSampleStorageEngine(File("/data/data/com.termux/files/home/"))
) : ViewModel() {
    private val _uiState = MutableStateFlow(AnalysisUiState())
    val uiState: StateFlow<AnalysisUiState> = _uiState.asStateFlow()

    fun analyzeSessionFileOrDemo(filePath: String?, buildingType: String = "RESIDENTIAL_CONCRETE", floors: Int = 3) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isAnalyzing = true,
                errorMessage = null,
                buildingType = buildingType,
                floors = floors,
                analyzedFilePath = filePath
            )

            try {
                val samples = withContext(Dispatchers.IO) {
                    if (filePath != null && File(filePath).exists()) {
                        val data = storageEngine.readSamplesFromFile(File(filePath))
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

                // 3. Identify initial top candidate frequency from magnitude/axes to evaluate physics plausibility
                val allPeaks = mainSpectrum.psdX.peaks + mainSpectrum.psdY.peaks + mainSpectrum.psdZ.peaks + mainSpectrum.psdMagnitude.peaks
                val bestCandidate = allPeaks.filter { it.frequencyHz in 0.3f..45.0f }.maxByOrNull { it.powerMagnitude }
                val candidateF0 = bestCandidate?.frequencyHz?.toDouble() ?: 3.2
                val candidateProminence = bestCandidate?.prominence ?: 0.65f

                val plausibility = physicsEngine.classifyFrequency(
                    f0Hz = candidateF0,
                    prominence = candidateProminence,
                    buildingType = buildingType,
                    floors = floors
                )

                // 4. Run ModalAnalyzer across spectra with adaptive persistence tracking
                val modalRes = modalAnalyzer.analyzeMultiAxisSpectrum(
                    spectrum = mainSpectrum,
                    slidingWindowSpectra = slidingSpectra,
                    plausibilityClassification = plausibility
                )

                _uiState.value = _uiState.value.copy(
                    isAnalyzing = false,
                    fundamentalFrequencyHz = modalRes.fundamentalFrequencyHz,
                    dominantAxis = modalRes.dominantAxis,
                    classificationLabel = modalRes.classification.classification.name,
                    modalResult = modalRes
                )
            } catch (e: Exception) {
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
}

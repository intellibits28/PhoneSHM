package com.ronin.phoneshm.core.modal

import com.ronin.phoneshm.core.dsp.MultiAxisSpectrumResult
import com.ronin.phoneshm.core.physics.PlausibilityClassificationResult

/**
 * ModalAnalysisResult details identified fundamental frequencies, persistence consistency,
 * and structural classification across X/Y/Z axes.
 */
data class ModalAnalysisResult(
    val fundamentalFrequencyHz: Double,
    val dominantAxis: String, // "X", "Y", "Z", or "MAGNITUDE"
    val confidence: Double,
    val persistence: Double, // Percentage of windows containing candidate within adaptive tolerance
    val adaptiveToleranceHz: Double,
    val classification: PlausibilityClassificationResult,
    val dominantPeaksTable: List<Pair<Double, Double>>
)

/**
 * ModalAnalyzer identifies structural resonance anomalies across multi-axis power spectra,
 * applying adaptive tolerance matching (+/- max(1%, deltaF * 2)) across sequential windows.
 */
interface ModalAnalyzer {
    /**
     * Identifies primary f0 modal frequency and tracking persistence across sliding windows.
     */
    fun analyzeMultiAxisSpectrum(
        spectrum: MultiAxisSpectrumResult,
        slidingWindowSpectra: List<MultiAxisSpectrumResult>,
        evaluatePhysics: (f0Hz: Double, prominence: Double) -> PlausibilityClassificationResult
    ): ModalAnalysisResult
}

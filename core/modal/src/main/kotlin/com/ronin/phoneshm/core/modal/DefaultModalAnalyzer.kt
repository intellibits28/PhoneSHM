package com.ronin.phoneshm.core.modal

import com.ronin.phoneshm.core.dsp.AxisPsdResult
import com.ronin.phoneshm.core.dsp.MultiAxisSpectrumResult
import com.ronin.phoneshm.core.dsp.Peak
import com.ronin.phoneshm.core.physics.FrequencyClassification
import com.ronin.phoneshm.core.physics.PlausibilityClassificationResult
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Default implementation of [ModalAnalyzer].
 *
 * Identifies primary structural natural frequencies ($f_0$) across multi-axis power spectral densities,
 * computes adaptive persistence across historical sliding windows ($\text{tolerance} = \max(1\%, \Delta f \times 2)$),
 * and aggregates directional peak signatures.
 */
class DefaultModalAnalyzer : ModalAnalyzer {

    override fun analyzeMultiAxisSpectrum(
        spectrum: MultiAxisSpectrumResult,
        slidingWindowSpectra: List<MultiAxisSpectrumResult>,
        plausibilityClassification: PlausibilityClassificationResult
    ): ModalAnalysisResult {
        // Collect candidate peaks from X, Y, Z, and Magnitude within valid structural bounds (0.3 Hz to 45.0 Hz)
        val candidatePeaks = mutableListOf<Pair<String, Peak>>()
        extractValidPeaks("X", spectrum.psdX, candidatePeaks)
        extractValidPeaks("Y", spectrum.psdY, candidatePeaks)
        extractValidPeaks("Z", spectrum.psdZ, candidatePeaks)
        extractValidPeaks("MAGNITUDE", spectrum.psdMagnitude, candidatePeaks)

        val dominantAxis: String
        val fundamentalFrequencyHz: Double
        val dominantPeakPower: Double
        val dominantPeakProminence: Double

        if (candidatePeaks.isEmpty()) {
            // Fallback: no peaks identified, find max PSD bin from magnitude spectrum directly
            val maxBin = findMaxBin(spectrum.psdMagnitude)
            if (maxBin != null) {
                fundamentalFrequencyHz = maxBin.first
                dominantPeakPower = maxBin.second
                dominantPeakProminence = 0.1
                dominantAxis = "MAGNITUDE"
            } else {
                return ModalAnalysisResult(
                    fundamentalFrequencyHz = 0.0,
                    dominantAxis = "UNKNOWN",
                    confidence = 0.0,
                    persistence = 0.0,
                    adaptiveToleranceHz = 0.1,
                    classification = plausibilityClassification,
                    dominantPeaksTable = emptyList()
                )
            }
        } else {
            // Select the candidate peak with highest power magnitude across all axes
            val bestCandidate = candidatePeaks.maxByOrNull { it.second.powerMagnitude }!!
            dominantAxis = bestCandidate.first
            fundamentalFrequencyHz = bestCandidate.second.frequencyHz.toDouble()
            dominantPeakPower = bestCandidate.second.powerMagnitude.toDouble()
            dominantPeakProminence = bestCandidate.second.prominence.toDouble()
        }

        // Calculate frequency bin resolution deltaF
        val deltaF = calculateDeltaF(spectrum.psdX)

        // Adaptive peak persistence tolerance: max(1% of f0, 2 * deltaF)
        val adaptiveToleranceHz = max(0.01 * fundamentalFrequencyHz, deltaF * 2.0)

        // Calculate persistence: what ratio of sliding windows contain a matching peak within adaptive tolerance?
        val persistence = if (slidingWindowSpectra.isEmpty()) {
            1.0 // Single-window session persistence is 1.0 by definition
        } else {
            var matchingWindowsCount = 0
            for (window in slidingWindowSpectra) {
                if (hasPeakInWindow(window, fundamentalFrequencyHz, adaptiveToleranceHz)) {
                    matchingWindowsCount++
                }
            }
            matchingWindowsCount.toDouble() / slidingWindowSpectra.size
        }

        // Build top peaks table across axes (sorted by power, deduplicated within 0.15 Hz)
        val peaksTable = if (candidatePeaks.isNotEmpty()) {
            buildDeduplicatedPeaksTable(candidatePeaks)
        } else {
            listOf(Pair(fundamentalFrequencyHz, dominantPeakPower))
        }

        // Calculate combined confidence (0.0 to 1.0)
        val peakConf = min(1.0, dominantPeakProminence * 1.5)
        val classConf = plausibilityClassification.confidence
        val combinedConfidence = min(1.0, (peakConf * 0.45) + (persistence * 0.35) + (classConf * 0.20))

        return ModalAnalysisResult(
            fundamentalFrequencyHz = fundamentalFrequencyHz,
            dominantAxis = dominantAxis,
            confidence = combinedConfidence,
            persistence = persistence,
            adaptiveToleranceHz = adaptiveToleranceHz,
            classification = plausibilityClassification,
            dominantPeaksTable = peaksTable
        )
    }

    private fun extractValidPeaks(axisName: String, axisResult: AxisPsdResult, dest: MutableList<Pair<String, Peak>>) {
        for (peak in axisResult.peaks) {
            if (peak.frequencyHz >= 0.3f && peak.frequencyHz <= 45.0f) {
                dest.add(Pair(axisName, peak))
            }
        }
    }

    private fun findMaxBin(axisResult: AxisPsdResult): Pair<Double, Double>? {
        if (axisResult.frequencies.isEmpty() || axisResult.powerSpectralDensity.isEmpty()) return null
        var maxIdx = -1
        var maxPsd = Float.NEGATIVE_INFINITY
        for (i in axisResult.frequencies.indices) {
            val freq = axisResult.frequencies[i]
            if (freq in 0.3f..45.0f) {
                val psd = axisResult.powerSpectralDensity[i]
                if (psd > maxPsd) {
                    maxPsd = psd
                    maxIdx = i
                }
            }
        }
        return if (maxIdx != -1) {
            Pair(axisResult.frequencies[maxIdx].toDouble(), maxPsd.toDouble())
        } else null
    }

    private fun calculateDeltaF(axisResult: AxisPsdResult): Double {
        return if (axisResult.frequencies.size >= 2) {
            abs((axisResult.frequencies[1] - axisResult.frequencies[0]).toDouble())
        } else {
            0.09765625 // Default 100 Hz / 1024
        }
    }

    private fun hasPeakInWindow(window: MultiAxisSpectrumResult, targetF0: Double, tolerance: Double): Boolean {
        val allWindowPeaks = mutableListOf<Peak>()
        allWindowPeaks.addAll(window.psdX.peaks)
        allWindowPeaks.addAll(window.psdY.peaks)
        allWindowPeaks.addAll(window.psdZ.peaks)
        allWindowPeaks.addAll(window.psdMagnitude.peaks)

        return allWindowPeaks.any { abs(it.frequencyHz.toDouble() - targetF0) <= tolerance }
    }

    private fun buildDeduplicatedPeaksTable(candidatePeaks: List<Pair<String, Peak>>): List<Pair<Double, Double>> {
        val sorted = candidatePeaks.map { Pair(it.second.frequencyHz.toDouble(), it.second.powerMagnitude.toDouble()) }
            .sortedByDescending { it.second }
        
        val deduplicated = mutableListOf<Pair<Double, Double>>()
        for (candidate in sorted) {
            if (deduplicated.none { abs(it.first - candidate.first) < 0.15 }) {
                deduplicated.add(candidate)
            }
            if (deduplicated.size >= 8) break // Keep top 8 distinct peaks
        }
        return deduplicated
    }
}

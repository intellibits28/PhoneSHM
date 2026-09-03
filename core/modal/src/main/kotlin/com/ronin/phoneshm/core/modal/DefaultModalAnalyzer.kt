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
        buildingType: String,
        evaluatePhysics: (f0Hz: Double, prominence: Double) -> PlausibilityClassificationResult
    ): ModalAnalysisResult {
        // Topology Masking for upper bound
        val upperLimitHz = if (buildingType.contains("TIMBER", ignoreCase = true) || buildingType.contains("WOOD", ignoreCase = true)) {
            20.0f
        } else {
            45.0f
        }

        // Collect candidate peaks from X, Y, Z, and Magnitude within valid structural bounds
        val candidatePeaks = mutableListOf<Pair<String, Peak>>()
        extractValidPeaks("X", spectrum.psdX, candidatePeaks, upperLimitHz)
        extractValidPeaks("Y", spectrum.psdY, candidatePeaks, upperLimitHz)
        extractValidPeaks("Z", spectrum.psdZ, candidatePeaks, upperLimitHz)
        extractValidPeaks("MAGNITUDE", spectrum.psdMagnitude, candidatePeaks, upperLimitHz)

        val dominantAxis: String
        val fundamentalFrequencyHz: Double
        val dominantPeakPower: Double
        val dominantPeakProminence: Double

        if (candidatePeaks.isEmpty()) {
            // Phase 0-C: No valid structural peaks found — do NOT fallback to max noise bin.
            // Return an explicit no-peak result to prevent false positive structural identification.
            return ModalAnalysisResult(
                fundamentalFrequencyHz = 0.0,
                dominantAxis = "NONE",
                confidence = 0.0,
                persistence = 0.0,
                adaptiveToleranceHz = 0.1,
                classification = evaluatePhysics(0.0, 0.0),
                dominantPeaksTable = emptyList(),
                prominenceRatio = Double.NaN,
                excitationSufficiency = ExcitationSufficiency.INSUFFICIENT
            )
        } else {
            // Select the candidate peak by weighting power magnitude with physics plausibility
            val bestCandidate = candidatePeaks.maxByOrNull { (_, peak) ->
                val classification = evaluatePhysics(peak.frequencyHz.toDouble(), peak.prominence.toDouble()).classification
                val multiplier = when (classification) {
                    FrequencyClassification.GLOBAL_MODE -> 1.0
                    FrequencyClassification.LOCAL_MODE -> 0.8
                    FrequencyClassification.BELOW_EXPECTED_RANGE -> 0.1
                    FrequencyClassification.SENSOR_ARTIFACT -> 0.01
                    FrequencyClassification.UNKNOWN -> 0.5
                }
                peak.powerMagnitude * multiplier
            }!!
            dominantAxis = bestCandidate.first
            fundamentalFrequencyHz = bestCandidate.second.frequencyHz.toDouble()
            dominantPeakPower = bestCandidate.second.powerMagnitude.toDouble()
            dominantPeakProminence = bestCandidate.second.prominence.toDouble()
        }

        // Calculate frequency bin resolution deltaF
        val deltaF = calculateDeltaF(spectrum.psdX)

        // Adaptive peak persistence tolerance: max(5% of f0, 4 * deltaF)
        val adaptiveToleranceHz = max(0.05 * fundamentalFrequencyHz, deltaF * 4.0)

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

        // Evaluate physics plausibility using the final resolved f0 and prominence
        val plausibilityClassification = evaluatePhysics(fundamentalFrequencyHz, dominantPeakProminence)

        // Calculate combined confidence (0.0 to 1.0)
        val peakConf = min(1.0, dominantPeakProminence * 1.5)
        val classConf = plausibilityClassification.confidence
        val rawConfidence = min(1.0, (peakConf * 0.45) + (persistence * 0.35) + (classConf * 0.20))
        // TASK 2: Multiplicative persistence gate. If persistence is below 25%, apply a severe penalty
        // to prevent pure noise (which naturally has 0% persistence but can hit high peak/class confidence)
        // from reaching the 60% baseline acceptance threshold.
        val persistenceFactor = if (persistence < 0.25) {
            0.3 + (0.7 * (persistence / 0.25))
        } else {
            1.0
        }
        val combinedConfidence = rawConfidence * persistenceFactor

        val dominantPsd = when (dominantAxis) {
            "X" -> spectrum.psdX.powerSpectralDensity
            "Y" -> spectrum.psdY.powerSpectralDensity
            "Z" -> spectrum.psdZ.powerSpectralDensity
            else -> spectrum.psdMagnitude.powerSpectralDensity
        }

        val medianPsd = medianOf(dominantPsd)
        val prominenceRatio = if (!medianPsd.isNaN() && medianPsd > 0.0) {
            dominantPeakPower / medianPsd
        } else {
            Double.NaN
        }

        // Phase 4: Peak Threshold Calibration
        // Adaptive thresholding: If a peak is highly persistent over time, we can trust a smaller prominence (e.g. 2.5x or 4dB SNR).
        // If it's transient (low persistence), we demand a high prominence (e.g. 6.0x or ~8dB SNR) to avoid noise spikes.
        val requiredProminence = when {
            persistence >= 0.8 -> 2.5 // Highly stable ambient peak
            persistence >= 0.5 -> 4.0 // Moderate stability
            else -> 6.0               // Transient / Impulsive peak
        }

        val excitationSufficiency = when {
            prominenceRatio.isNaN() -> ExcitationSufficiency.UNKNOWN
            prominenceRatio < requiredProminence -> ExcitationSufficiency.INSUFFICIENT
            else -> ExcitationSufficiency.SUFFICIENT
        }

        return ModalAnalysisResult(
            fundamentalFrequencyHz = fundamentalFrequencyHz,
            dominantAxis = dominantAxis,
            confidence = combinedConfidence,
            persistence = persistence,
            adaptiveToleranceHz = adaptiveToleranceHz,
            classification = plausibilityClassification,
            dominantPeaksTable = peaksTable,
            prominenceRatio = prominenceRatio,
            excitationSufficiency = excitationSufficiency
        )
    }

    private fun extractValidPeaks(axisName: String, axisResult: AxisPsdResult, dest: MutableList<Pair<String, Peak>>, upperLimitHz: Float) {
        for (peak in axisResult.peaks) {
            if (peak.frequencyHz >= 0.3f && peak.frequencyHz <= upperLimitHz) {
                dest.add(Pair(axisName, peak))
            }
        }
    }

    private fun findMaxBin(axisResult: AxisPsdResult, upperLimitHz: Float): Pair<Double, Double>? {
        if (axisResult.frequencies.isEmpty() || axisResult.powerSpectralDensity.isEmpty()) return null
        var maxIdx = -1
        var maxPsd = Float.NEGATIVE_INFINITY
        for (i in axisResult.frequencies.indices) {
            val freq = axisResult.frequencies[i]
            if (freq in 0.3f..upperLimitHz) {
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
        return deduplicated.take(3)
    }

    private fun medianOf(array: FloatArray): Double {
        if (array.isEmpty()) return Double.NaN
        val sorted = array.sortedArray()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[mid - 1].toDouble() + sorted[mid].toDouble()) / 2.0
        } else {
            sorted[mid].toDouble()
        }
    }
}

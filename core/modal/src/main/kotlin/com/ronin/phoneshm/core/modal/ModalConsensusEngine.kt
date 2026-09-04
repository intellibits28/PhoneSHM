package com.ronin.phoneshm.core.modal

import com.ronin.phoneshm.core.dsp.NativeFddResult
import com.ronin.phoneshm.core.dsp.RdtSsiResult
import kotlin.math.abs

enum class ConsensusStatus {
    AGREED,
    PARTIAL,
    DISAGREED,
    INSUFFICIENT
}

data class ModalConsensusResult(
    val welchF0: Double?,
    val efddF0: Double?,
    val ssiF0: Double?,
    val consensusF0: Double?,
    val agreementScore: Float,
    val status: ConsensusStatus
)

/**
 * Evaluates agreement between different Operational Modal Analysis (OMA) algorithms.
 * - Welch PSD (Frequency Domain)
 * - EFDD (Frequency Domain)
 * - RDT-SSI (Time Domain)
 */
class ModalConsensusEngine {
    
    fun evaluateConsensus(
        modalRes: ModalAnalysisResult,
        efddResult: NativeFddResult?,
        ssiResult: RdtSsiResult?
    ): ModalConsensusResult {
        // Collect all candidates
        val welchF0 = if (modalRes.fundamentalFrequencyHz > 0.0) modalRes.fundamentalFrequencyHz else null
        val efddCandidates = efddResult?.peakFrequencies?.map { it.toDouble() } ?: emptyList()
        val ssiCandidates = ssiResult?.poles?.map { it.frequencyHz.toDouble() } ?: emptyList()

        val tolerancePct = 0.05

        // Extract possible cluster centers
        val allCenters = mutableListOf<Double>()
        if (welchF0 != null) allCenters.add(welchF0)
        allCenters.addAll(efddCandidates.take(3))
        allCenters.addAll(ssiCandidates.take(3))

        var bestCluster = emptyList<Double>()
        var bestWelch: Double? = null
        var bestEfdd: Double? = null
        var bestSsi: Double? = null

        // Find the frequency that appears in the most methods (largest cluster)
        for (center in allCenters) {
            val wMatch = if (welchF0 != null && isWithinTolerance(center, welchF0, tolerancePct)) welchF0 else null
            val eMatch = efddCandidates.firstOrNull { isWithinTolerance(center, it, tolerancePct) }
            val sMatch = ssiCandidates.firstOrNull { isWithinTolerance(center, it, tolerancePct) }

            val currentCluster = listOfNotNull(wMatch, eMatch, sMatch)
            if (currentCluster.size > bestCluster.size) {
                bestCluster = currentCluster
                bestWelch = wMatch
                bestEfdd = eMatch
                bestSsi = sMatch
            }
        }

        // If no agreement (cluster size <= 1), fallback to the primary peaks
        val finalWelch = bestWelch ?: welchF0
        val finalEfdd = bestEfdd ?: efddCandidates.firstOrNull()
        val finalSsi = bestSsi ?: ssiCandidates.firstOrNull()

        val agreedRatio = bestCluster.size.toFloat() / 3f
        val finalStatus = when (bestCluster.size) {
            3 -> ConsensusStatus.AGREED
            2 -> ConsensusStatus.PARTIAL
            else -> ConsensusStatus.DISAGREED
        }

        val methodsCount = listOfNotNull(finalWelch, finalEfdd, finalSsi).size
        if (methodsCount == 0) {
            return ModalConsensusResult(null, null, null, null, 0.0f, ConsensusStatus.INSUFFICIENT)
        }

        return ModalConsensusResult(
            welchF0 = finalWelch,
            efddF0 = finalEfdd,
            ssiF0 = finalSsi,
            consensusF0 = if (bestCluster.size > 1) bestCluster.average() else null,
            agreementScore = agreedRatio,
            status = finalStatus
        )
    }

    private fun isWithinTolerance(f1: Double, f2: Double, tol: Double): Boolean {
        val diff = abs(f1 - f2)
        val avg = (f1 + f2) / 2.0
        return (diff / avg) <= tol
    }
}

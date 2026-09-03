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
        // Welch is the baseline unless it explicitly says INSUFFICIENT
        val welchF0 = if (modalRes.excitationSufficiency != ExcitationSufficiency.INSUFFICIENT && modalRes.fundamentalFrequencyHz > 0.0) {
            modalRes.fundamentalFrequencyHz
        } else null

        val tolerancePct = 0.05

        // EFDD: Use the peak that aligns with Welch (within 5%), otherwise fallback to the strongest peak
        val efddF0 = if (welchF0 != null && efddResult != null) {
            val matchedFreq = efddResult.peakFrequencies.firstOrNull { abs(it - welchF0) / welchF0 <= tolerancePct }
            (matchedFreq ?: efddResult.peakFrequencies.firstOrNull())?.toDouble()
        } else {
            efddResult?.peakFrequencies?.firstOrNull()?.toDouble()
        }

        // SSI: Use the stable pole that aligns with Welch (within 5%), otherwise fallback to the most stable pole
        val ssiF0 = if (welchF0 != null && ssiResult != null) {
            val matchedPole = ssiResult.poles.firstOrNull { abs(it.frequencyHz - welchF0) / welchF0 <= tolerancePct }
            (matchedPole?.frequencyHz ?: ssiResult.poles.firstOrNull()?.frequencyHz)?.toDouble()
        } else {
            ssiResult?.poles?.firstOrNull()?.frequencyHz?.toDouble()
        }

        val methods = listOfNotNull(welchF0, efddF0, ssiF0)
        
        if (methods.isEmpty()) {
            return ModalConsensusResult(
                welchF0 = welchF0,
                efddF0 = efddF0,
                ssiF0 = ssiF0,
                consensusF0 = null,
                agreementScore = 0.0f,
                status = ConsensusStatus.INSUFFICIENT
            )
        }
        
        if (methods.size == 1) {
            return ModalConsensusResult(
                welchF0 = welchF0,
                efddF0 = efddF0,
                ssiF0 = ssiF0,
                consensusF0 = methods[0],
                agreementScore = 0.33f,
                status = ConsensusStatus.PARTIAL
            )
        }

        // Check agreement (±5% tolerance)
        val tolerance = 0.05
        
        val cluster = mutableListOf<Double>()
        if (welchF0 != null) {
            cluster.add(welchF0)
            if (efddF0 != null && isWithinTolerance(welchF0, efddF0, tolerance)) cluster.add(efddF0)
            if (ssiF0 != null && isWithinTolerance(welchF0, ssiF0, tolerance)) cluster.add(ssiF0)
        } else if (efddF0 != null) {
            cluster.add(efddF0)
            if (ssiF0 != null && isWithinTolerance(efddF0, ssiF0, tolerance)) cluster.add(ssiF0)
        } else if (ssiF0 != null) {
            cluster.add(ssiF0)
        }

        val agreedRatio = cluster.size.toFloat() / 3f // Score out of all 3 possible methods
        val finalStatus = when {
            cluster.size == 3 -> ConsensusStatus.AGREED
            cluster.size == 2 -> ConsensusStatus.PARTIAL
            else -> ConsensusStatus.DISAGREED
        }

        return ModalConsensusResult(
            welchF0 = welchF0,
            efddF0 = efddF0,
            ssiF0 = ssiF0,
            consensusF0 = if (cluster.size > 1) cluster.average() else cluster.firstOrNull(),
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

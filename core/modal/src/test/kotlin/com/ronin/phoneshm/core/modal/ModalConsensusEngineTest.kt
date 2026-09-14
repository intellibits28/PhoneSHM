package com.ronin.phoneshm.core.modal

import com.ronin.phoneshm.core.dsp.NativeFddResult
import com.ronin.phoneshm.core.dsp.RdtSsiPole
import com.ronin.phoneshm.core.dsp.RdtSsiResult
import com.ronin.phoneshm.core.physics.FrequencyClassification
import com.ronin.phoneshm.core.physics.PlausibilityClassificationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ModalConsensusEngineTest {

    private lateinit var engine: ModalConsensusEngine

    @Before
    fun setUp() {
        engine = ModalConsensusEngine()
    }

    private fun createModalResult(f0: Double): ModalAnalysisResult {
        return ModalAnalysisResult(
            fundamentalFrequencyHz = f0,
            dominantAxis = "Z",
            confidence = 0.9,
            persistence = 0.85,
            adaptiveToleranceHz = 0.2,
            classification = PlausibilityClassificationResult(
                classification = FrequencyClassification.GLOBAL_MODE,
                confidence = 0.95,
                explanation = "Plausible"
            ),
            dominantPeaksTable = listOf(Pair(f0, 10.0)),
            prominenceRatio = 5.0,
            excitationSufficiency = ExcitationSufficiency.SUFFICIENT
        )
    }

    private fun createEfddResult(vararg peaks: Float): NativeFddResult {
        return NativeFddResult(
            frequencies = floatArrayOf(),
            firstSingularValues = floatArrayOf(),
            peakFrequencies = peaks,
            peakMagnitudes = FloatArray(peaks.size) { 1.0f },
            peakProminences = FloatArray(peaks.size) { 2.0f },
            peakDampingRatios = FloatArray(peaks.size) { 0.02f }
        )
    }

    private fun createSsiResult(vararg freqs: Float): RdtSsiResult {
        return RdtSsiResult(
            poles = freqs.map { RdtSsiPole(frequencyHz = it, dampingRatio = 0.02f, modelOrder = 10) }
        )
    }

    @Test
    fun evaluateConsensus_allThreeMethodsAgree_returnsAgreedStatus() {
        val modalRes = createModalResult(7.85)
        val efddRes = createEfddResult(7.853f)
        val ssiRes = createSsiResult(7.90f)

        val result = engine.evaluateConsensus(modalRes, efddRes, ssiRes)

        assertEquals(ConsensusStatus.AGREED, result.status)
        assertEquals(1.0f, result.agreementScore, 0.01f)
        assertNotNull(result.consensusF0)
        assertEquals(7.867, result.consensusF0!!, 0.05)
        assertEquals(7.85, result.welchF0!!, 0.001)
        assertEquals(7.853, result.efddF0!!, 0.001)
        assertEquals(7.90, result.ssiF0!!, 0.001)
    }

    @Test
    fun evaluateConsensus_twoMethodsAgree_returnsPartialStatus() {
        val modalRes = createModalResult(7.85)
        val efddRes = createEfddResult(7.86f)
        val ssiRes = createSsiResult(15.2f) // SSI diverged or detected a higher overtone

        val result = engine.evaluateConsensus(modalRes, efddRes, ssiRes)

        assertEquals(ConsensusStatus.PARTIAL, result.status)
        assertEquals(2f / 3f, result.agreementScore, 0.01f)
        assertNotNull(result.consensusF0)
        assertEquals(7.855, result.consensusF0!!, 0.01)
    }

    @Test
    fun evaluateConsensus_ssiMissingOrNull_returnsPartialIfTwoAgree() {
        val modalRes = createModalResult(8.20)
        val efddRes = createEfddResult(8.22f)

        val result = engine.evaluateConsensus(modalRes, efddRes, null)

        assertEquals(ConsensusStatus.PARTIAL, result.status)
        assertEquals(2f / 3f, result.agreementScore, 0.01f)
        assertNotNull(result.consensusF0)
        assertEquals(8.21, result.consensusF0!!, 0.02)
        assertNull(result.ssiF0)
    }

    @Test
    fun evaluateConsensus_allMethodsDisagree_returnsDisagreedStatus() {
        val modalRes = createModalResult(3.5)
        val efddRes = createEfddResult(9.0f)
        val ssiRes = createSsiResult(18.5f)

        val result = engine.evaluateConsensus(modalRes, efddRes, ssiRes)

        assertEquals(ConsensusStatus.DISAGREED, result.status)
        assertEquals(1f / 3f, result.agreementScore, 0.01f)
        assertNull(result.consensusF0)
        assertEquals(3.5, result.welchF0)
        assertEquals(9.0, result.efddF0)
        assertEquals(18.5, result.ssiF0)
    }

    @Test
    fun evaluateConsensus_noValidPeaks_returnsInsufficientStatus() {
        val modalRes = createModalResult(0.0) // No peak found in Welch
        val efddRes = createEfddResult() // Empty peaks
        val ssiRes = createSsiResult() // Empty poles

        val result = engine.evaluateConsensus(modalRes, efddRes, ssiRes)

        assertEquals(ConsensusStatus.INSUFFICIENT, result.status)
        assertEquals(0.0f, result.agreementScore, 0.01f)
        assertNull(result.consensusF0)
        assertNull(result.welchF0)
        assertNull(result.efddF0)
        assertNull(result.ssiF0)
    }

    @Test
    fun evaluateConsensus_secondaryPeakMatchesCluster_findsBestAgreement() {
        // Welch f0 = 8.0 Hz
        // EFDD top peak = 3.2 Hz (local spurious mode), second peak = 8.04 Hz
        // SSI top peak = 8.02 Hz
        val modalRes = createModalResult(8.0)
        val efddRes = createEfddResult(3.2f, 8.04f, 15.0f)
        val ssiRes = createSsiResult(8.02f)

        val result = engine.evaluateConsensus(modalRes, efddRes, ssiRes)

        // It should match 8.0, 8.04, 8.02 across the methods and achieve AGREED!
        assertEquals(ConsensusStatus.AGREED, result.status)
        assertEquals(1.0f, result.agreementScore, 0.01f)
        assertEquals(8.04, result.efddF0!!, 0.01)
        assertNotNull(result.consensusF0)
        assertEquals(8.02, result.consensusF0!!, 0.02)
    }
}

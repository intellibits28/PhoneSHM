package com.ronin.phoneshm.core.modal

import com.ronin.phoneshm.core.dsp.AxisPsdResult
import com.ronin.phoneshm.core.dsp.MultiAxisSpectrumResult
import com.ronin.phoneshm.core.dsp.Peak
import com.ronin.phoneshm.core.dsp.WelchPsdParameters
import com.ronin.phoneshm.core.physics.FrequencyClassification
import com.ronin.phoneshm.core.physics.PlausibilityClassificationResult
import org.junit.Assert.assertEquals
import org.junit.Test

class ModalAnalyzerTest {

    private class FakeModalAnalyzer : ModalAnalyzer {
        override fun analyzeMultiAxisSpectrum(
            spectrum: MultiAxisSpectrumResult,
            slidingWindowSpectra: List<MultiAxisSpectrumResult>,
            plausibilityClassification: PlausibilityClassificationResult
        ): ModalAnalysisResult {
            return ModalAnalysisResult(
                fundamentalFrequencyHz = 8.17,
                dominantAxis = "X",
                confidence = 0.92,
                persistence = 0.95,
                adaptiveToleranceHz = 0.194, // max(0.0817, 0.097 * 2)
                classification = plausibilityClassification,
                dominantPeaksTable = listOf(Pair(8.17, 12.5), Pair(24.3, 3.2))
            )
        }
    }

    @Test
    fun testModalAnalysisResult() {
        val analyzer = FakeModalAnalyzer()
        val dummyAxis = AxisPsdResult(floatArrayOf(8.17f), floatArrayOf(12.5f), listOf(Peak(8.17f, 12.5f, 0.8f)))
        val dummySpec = MultiAxisSpectrumResult(dummyAxis, dummyAxis, dummyAxis, dummyAxis, WelchPsdParameters())
        val classification = PlausibilityClassificationResult(
            classification = FrequencyClassification.GLOBAL_MODE,
            confidence = 0.9,
            explanation = "Valid"
        )

        val result = analyzer.analyzeMultiAxisSpectrum(dummySpec, emptyList(), classification)
        assertEquals(8.17, result.fundamentalFrequencyHz, 0.001)
        assertEquals(0.95, result.persistence, 0.001)
    }
}

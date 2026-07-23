package com.ronin.phoneshm.core.modal

import com.ronin.phoneshm.core.dsp.AxisPsdResult
import com.ronin.phoneshm.core.dsp.MultiAxisSpectrumResult
import com.ronin.phoneshm.core.dsp.Peak
import com.ronin.phoneshm.core.dsp.WelchPsdParameters
import com.ronin.phoneshm.core.dsp.WelchPsdOutput
import com.ronin.phoneshm.core.physics.FrequencyClassification
import com.ronin.phoneshm.core.physics.PlausibilityClassificationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultModalAnalyzerTest {

    private val analyzer = DefaultModalAnalyzer()

    private fun createDummySpectrum(
        xPeak: Peak? = null,
        yPeak: Peak? = null,
        zPeak: Peak? = null,
        magPeak: Peak? = null
    ): MultiAxisSpectrumResult {
        val freqs = floatArrayOf(0.0f, 1.0f, 2.0f, 3.0f, 8.17f, 12.0f, 25.0f)
        val psd = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 10.0f, 1.0f, 0.5f)

        val xAxis = AxisPsdResult(freqs, psd, xPeak?.let { listOf(it) } ?: emptyList())
        val yAxis = AxisPsdResult(freqs, psd, yPeak?.let { listOf(it) } ?: emptyList())
        val zAxis = AxisPsdResult(freqs, psd, zPeak?.let { listOf(it) } ?: emptyList())
        val magAxis = AxisPsdResult(freqs, psd, magPeak?.let { listOf(it) } ?: emptyList())

        return MultiAxisSpectrumResult(xAxis, yAxis, zAxis, magAxis, WelchPsdOutput(WelchPsdParameters(), 1, 0.0977f, null))
    }

    @Test
    fun testDominantAxisAndFundamentalFrequencySelection() {
        val spec = createDummySpectrum(
            xPeak = Peak(3.2f, 5.0f, 0.6f),
            yPeak = Peak(8.17f, 15.2f, 0.9f), // Highest power magnitude across axes
            zPeak = Peak(12.0f, 8.0f, 0.7f),
            magPeak = Peak(8.17f, 14.0f, 0.85f)
        )
        val classification = PlausibilityClassificationResult(
            classification = FrequencyClassification.GLOBAL_MODE,
            confidence = 0.9,
            explanation = "Valid global fundamental mode"
        )

        val result = analyzer.analyzeMultiAxisSpectrum(spec, emptyList(), classification)

        assertEquals(8.17, result.fundamentalFrequencyHz, 0.001)
        assertEquals("Y", result.dominantAxis)
        assertTrue(result.confidence > 0.8)
        assertTrue(result.dominantPeaksTable.isNotEmpty())
        assertEquals(8.17, result.dominantPeaksTable.first().first, 0.001)
    }

    @Test
    fun testAdaptivePersistenceAcrossSlidingWindows() {
        val mainSpec = createDummySpectrum(xPeak = Peak(8.17f, 10.0f, 0.8f))
        
        // Window 1: exact peak at 8.17 Hz -> match
        val win1 = createDummySpectrum(xPeak = Peak(8.17f, 9.5f, 0.8f))
        // Window 2: peak at 8.25 Hz -> within adaptive tolerance (deltaF = 1.0 Hz in dummy -> tolerance = max(0.0817, 2.0) = 2.0 Hz) -> match
        val win2 = createDummySpectrum(yPeak = Peak(8.25f, 9.0f, 0.75f))
        // Window 3: peak far away at 25.0 Hz -> no match
        val win3 = createDummySpectrum(zPeak = Peak(25.0f, 5.0f, 0.5f))

        val classification = PlausibilityClassificationResult(
            classification = FrequencyClassification.GLOBAL_MODE,
            confidence = 0.9,
            explanation = "Valid"
        )

        val result = analyzer.analyzeMultiAxisSpectrum(mainSpec, listOf(win1, win2, win3), classification)

        // 2 out of 3 windows contain a peak matching f0 (8.17 Hz) within adaptive tolerance
        assertEquals(2.0 / 3.0, result.persistence, 0.001)
    }

    @Test
    fun testFallbackWhenNoCandidatePeaksPresent() {
        val emptySpec = createDummySpectrum()
        val classification = PlausibilityClassificationResult(
            classification = FrequencyClassification.UNKNOWN,
            confidence = 0.4,
            explanation = "No peaks"
        )

        val result = analyzer.analyzeMultiAxisSpectrum(emptySpec, emptyList(), classification)
        // Should fallback to highest PSD bin in valid range (in dummy spectrum, 8.17 Hz has psd 10.0)
        assertEquals(8.17, result.fundamentalFrequencyHz, 0.001)
        assertEquals("MAGNITUDE", result.dominantAxis)
    }
}

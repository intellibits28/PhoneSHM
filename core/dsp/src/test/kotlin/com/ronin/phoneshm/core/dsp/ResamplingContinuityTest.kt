package com.ronin.phoneshm.core.dsp

import com.ronin.phoneshm.core.sensor.AccelerationSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

class ResamplingContinuityTest {

    private val dspEngine = WelchPsdEngine()

    @Test
    fun testPchipHarmonicRecoveryUnderJitter() {
        val fs = 100f
        val durationSec = 30.0
        val targetF0 = 2.4f // Structural fundamental frequency
        val rnd = Random(42)

        // Generate non-uniform timestamps with jitter (nominal 10ms +/- 3ms)
        val samples = mutableListOf<AccelerationSample>()
        var currentTsNs = 0L
        while (currentTsNs < (durationSec * 1e9).toLong()) {
            val tSec = currentTsNs / 1e9
            val signalVal = sin(2.0 * PI * targetF0 * tSec).toFloat()
            // Ambient noise
            val noise = (rnd.nextGaussian() * 0.02).toFloat()
            samples.add(AccelerationSample(currentTsNs, signalVal + noise, (signalVal + noise) * 0.8f, 0f))

            val dtMs = 10.0 + (rnd.nextDouble() * 6.0 - 3.0) // 7ms to 13ms
            currentTsNs += (dtMs * 1e6).toLong()
        }

        // Resample with PCHIP
        val pchipOutput = dspEngine.resampleToUniformGrid(samples, fs, InterpolationMethod.PCHIP_CUBIC)
        assertTrue(pchipOutput.isContinuityPassed)
        assertEquals(0.0, pchipOutput.missingTimeRatio, 1e-4)

        // Resample with Linear
        val linearOutput = dspEngine.resampleToUniformGrid(samples, fs, InterpolationMethod.LINEAR)
        assertTrue(linearOutput.isContinuityPassed)

        val params = WelchPsdParameters(fftSize = 1024, overlapPercentage = 0.5f)
        val pchipSpectrum = dspEngine.calculateMultiAxisWelchPsd(pchipOutput.samples, fs, params, gapMask = pchipOutput.gapMask)
        val linearSpectrum = dspEngine.calculateMultiAxisWelchPsd(linearOutput.samples, fs, params, gapMask = linearOutput.gapMask)

        // Find peak closest to targetF0
        val pchipPeak = pchipSpectrum.psdX.peaks.minByOrNull { kotlin.math.abs(it.frequencyHz - targetF0) }
        val linearPeak = linearSpectrum.psdX.peaks.minByOrNull { kotlin.math.abs(it.frequencyHz - targetF0) }

        assertNotNull("PCHIP spectrum should identify f0 peak", pchipPeak)
        assertNotNull("Linear spectrum should identify f0 peak", linearPeak)
        assertEquals(targetF0, pchipPeak!!.frequencyHz, 0.15f)
        assertEquals(targetF0, linearPeak!!.frequencyHz, 0.15f)

        // PCHIP preserves peak prominence with monotonic Hermite interpolation without flattening
        assertTrue("PCHIP should have healthy peak prominence", pchipPeak.prominence >= 1.0f)
    }

    @Test
    fun testGapMaskWindowRejectionOnIsolatedGap() {
        val fs = 100f
        val durationSec = 35.0
        val targetF0 = 1.8f

        // Generate 35s recording at 100Hz with an isolated 200ms gap at 15s
        val samples = mutableListOf<AccelerationSample>()
        var currentTsNs = 0L
        while (currentTsNs < (durationSec * 1e9).toLong()) {
            val tSec = currentTsNs / 1e9
            val signalVal = sin(2.0 * PI * targetF0 * tSec).toFloat()

            // If we are at ~15s, inject a 200ms gap
            if (tSec in 15.0..15.005) {
                samples.add(AccelerationSample(currentTsNs, signalVal, 0f, 0f))
                currentTsNs += 200_000_000L // 200ms jump
            } else {
                samples.add(AccelerationSample(currentTsNs, signalVal, 0f, 0f))
                currentTsNs += 10_000_000L // normal 10ms
            }
        }

        val resamplingOutput = dspEngine.resampleToUniformGrid(samples, fs, InterpolationMethod.PCHIP_CUBIC)

        // 200ms gap in 35s is ~0.57% missing time -> well under 5% threshold
        assertTrue(resamplingOutput.isContinuityPassed)
        assertTrue(resamplingOutput.missingTimeRatio < 0.05)
        assertEquals(200.0, resamplingOutput.maxGapMs, 5.0)

        // Check that gapMask marks the interpolated samples across the 200ms gap
        val markedGapCount = resamplingOutput.gapMask.count { it }
        assertTrue("Gap mask should contain marked samples for the 200ms gap", markedGapCount in 18..22)

        // Check timestamps of marked samples are around 15.0s
        for (i in resamplingOutput.gapMask.indices) {
            if (resamplingOutput.gapMask[i]) {
                val tSec = (resamplingOutput.samples[i].timestampNs - samples.first().timestampNs) / 1e9
                assertTrue("Marked gap sample must be within the 15.0-15.3s window", tSec in 14.95..15.25)
            }
        }

        // Run Welch PSD with gapMask
        val params = WelchPsdParameters(fftSize = 1024, overlapPercentage = 0.5f)
        val specWithGapMask = dspEngine.calculateMultiAxisWelchPsd(
            resamplingOutput.samples, fs, params, gapMask = resamplingOutput.gapMask
        )

        // Run Welch PSD without gapMask
        val specWithoutGapMask = dspEngine.calculateMultiAxisWelchPsd(
            resamplingOutput.samples, fs, params, gapMask = null
        )

        // Gated PSD excludes the corrupted jump window, preserving clean structural resonance
        val psdWithGap = specWithGapMask.psdX.powerSpectralDensity
        val psdWithoutGap = specWithoutGapMask.psdX.powerSpectralDensity

        // High frequency noise floor comparison (e.g. 25Hz - 45Hz)
        val deltaF = specWithGapMask.output.effectiveDeltaFHz
        val bin25Hz = (25.0f / deltaF).toInt()
        val bin45Hz = (45.0f / deltaF).toInt()
        val hfNoiseGated = psdWithGap.slice(bin25Hz..bin45Hz).average()
        val hfNoiseUngated = psdWithoutGap.slice(bin25Hz..bin45Hz).average()

        assertTrue(
            "Gated PSD must have lower or equal high-frequency leakage than ungated (gated: $hfNoiseGated vs ungated: $hfNoiseUngated)",
            hfNoiseGated <= hfNoiseUngated
        )

        // Peak at targetF0 should be cleanly preserved in the gated PSD
        val peak = specWithGapMask.psdX.peaks.minByOrNull { kotlin.math.abs(it.frequencyHz - targetF0) }
        assertNotNull(peak)
        assertEquals(targetF0, peak!!.frequencyHz, 0.15f)
    }

    @Test
    fun testContinuityFailureWhenMissingRatioExceedsFivePercent() {
        val fs = 100f
        val durationSec = 30.0

        // Create 30s session with 4 distinct 500ms gaps = 2.0s total missing time (> 6.6% > 5%)
        val samples = mutableListOf<AccelerationSample>()
        var currentTsNs = 0L
        val gapTimesSec = listOf(5.0, 10.0, 15.0, 20.0)
        var gapIdx = 0

        while (currentTsNs < (durationSec * 1e9).toLong()) {
            val tSec = currentTsNs / 1e9
            if (gapIdx < gapTimesSec.size && tSec >= gapTimesSec[gapIdx]) {
                samples.add(AccelerationSample(currentTsNs, 0.1f, 0.1f, 0f))
                currentTsNs += 500_000_000L // 500ms gap
                gapIdx++
            } else {
                samples.add(AccelerationSample(currentTsNs, 0.1f, 0.1f, 0f))
                currentTsNs += 10_000_000L // 10ms
            }
        }

        val output = dspEngine.resampleToUniformGrid(samples, fs, InterpolationMethod.PCHIP_CUBIC)

        assertFalse("Total missing time exceeds 5% of duration, isContinuityPassed must be false", output.isContinuityPassed)
        assertTrue("Missing time ratio must be > 0.05", output.missingTimeRatio > 0.05)
        assertEquals(500.0, output.maxGapMs, 5.0)
        assertTrue("Total missing time should be approximately 2.0s", output.totalMissingTimeSec in 1.9..2.1)
    }
}

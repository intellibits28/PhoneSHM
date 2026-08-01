package com.ronin.phoneshm.core.dsp

import com.ronin.phoneshm.core.sensor.AccelerationSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * RealDataTest — Parseval consistency check on actual device recordings.
 *
 * Validates that ∫ PSD(f) df  ≈  (1/N) Σ x²(t) when both spectral and time-domain
 * paths apply identical processing (gravity removal → settling trim → HPF filtfilt).
 *
 * The magnitude PSD is the SUM of per-axis PSDs (psdX + psdY + psdZ), so
 * the integrated power should equal sum of per-axis time-domain variances.
 *
 * Expected mismatch sources (combined ~2-5%):
 *   - Hanning window ENBW factor vs rectangular variance
 *   - Per-segment detrending removes a small amount of low-frequency energy
 *   - Finite segment count (Welch averaging noise)
 *
 * Tolerance: 5% overall, 5% per-axis.
 *
 * Historical note: An earlier version of this test showed a ~2.6x mismatch
 * because the time-domain RMS was computed on raw gravity-free samples (before HPF
 * and settling trim) while the PSD was computed after. Both paths now use the
 * identical pipeline, and the ratio is ~0.98 on real Note8 data.
 */
class RealDataTest {

    private fun loadFromResources(filename: String): List<AccelerationSample> {
        val stream = javaClass.classLoader!!.getResourceAsStream("fixtures/$filename")
            ?: error("Test fixture not found: fixtures/$filename")
        val bytes = stream.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val samples = mutableListOf<AccelerationSample>()
        while (buf.remaining() >= 20) {
            val ts = buf.long
            val x = buf.float
            val y = buf.float
            val z = buf.float
            samples.add(AccelerationSample(ts, x, y, z))
        }
        return samples
    }

    @Test
    fun testParsevalConsistencyOnRealRecording() {
        val samples = loadFromResources("a562eabf-040f-4979-a438-9e1877f910fc.bin")
        assertTrue("Expected >= 2048 samples, got ${samples.size}", samples.size >= 2048)

        val engine = WelchPsdEngine()
        val params = WelchPsdParameters(fftSize = 2048, overlapPercentage = 0.5f)
        val sampleRateHz = 100f

        // Run the full pipeline (gravity removal + settling + HPF + Welch PSD)
        val result = engine.calculateMultiAxisWelchPsd(samples, sampleRateHz, params)
        val settlingN = result.output.settlingWindow?.settlingDurationSamples ?: 0
        assertTrue("Need at least 1 Welch segment", result.output.actualSegmentCount >= 1)

        // === SPECTRAL DOMAIN: integrate psdMagnitude = psdX + psdY + psdZ ===
        val psd = result.psdMagnitude.powerSpectralDensity
        val df = result.output.effectiveDeltaFHz.toDouble()
        var integratedPower = 0.0
        for (p in psd) integratedPower += p * df

        // === TIME DOMAIN: apply identical processing pipeline ===
        val gravResult = engine.removeGravityAndDetrend(samples)
        val gfSamples = gravResult.gravityFreeSamples
        val usableN = samples.size - settlingN

        val rawX = FloatArray(usableN) { gfSamples[it + settlingN].x }
        val rawY = FloatArray(usableN) { gfSamples[it + settlingN].y }
        val rawZ = FloatArray(usableN) { gfSamples[it + settlingN].z }

        val filtX = engine.highPassFilter(rawX, sampleRateHz, 0.5f)
        val filtY = engine.highPassFilter(rawY, sampleRateHz, 0.5f)
        val filtZ = engine.highPassFilter(rawZ, sampleRateHz, 0.5f)

        // Sum of per-axis variances
        var timeSumSq = 0.0
        for (i in filtX.indices) {
            val x = filtX[i].toDouble()
            val y = filtY[i].toDouble()
            val z = filtZ[i].toDouble()
            timeSumSq += x * x + y * y + z * z
        }
        val timeVariance = timeSumSq / filtX.size

        val psdRms = sqrt(integratedPower)
        val timeRms = sqrt(timeVariance)
        val overallRatio = timeRms / psdRms

        println("Parseval Check: PSD_RMS=${psdRms}, Time_RMS=${timeRms}, Ratio=$overallRatio")

        // Overall Parseval: time-domain and spectral-domain should agree within 5%
        assertEquals(
            "Parseval mismatch: time/PSD ratio should be ~1.0",
            1.0, overallRatio, 0.05
        )

        // Per-axis Parseval checks
        for ((label, psdAxis, filtAxis) in listOf(
            Triple("X", result.psdX, filtX),
            Triple("Y", result.psdY, filtY),
            Triple("Z", result.psdZ, filtZ)
        )) {
            var axisPsdPower = 0.0
            for (p in psdAxis.powerSpectralDensity) axisPsdPower += p * df
            var axisTimeSumSq = 0.0
            for (v in filtAxis) axisTimeSumSq += v.toDouble() * v.toDouble()
            val axisTimeVar = axisTimeSumSq / filtAxis.size
            val axisRatio = sqrt(axisTimeVar) / sqrt(axisPsdPower)

            println("  $label-axis: PSD_RMS=${sqrt(axisPsdPower)}, Time_RMS=${sqrt(axisTimeVar)}, Ratio=$axisRatio")

            assertEquals(
                "Parseval mismatch on $label-axis: ratio should be ~1.0",
                1.0, axisRatio, 0.05
            )
        }
    }

    @Test
    fun testParsevalConsistencyOnSecondFixture() {
        val samples = loadFromResources("4c7398ab-da37-4ab9-91c7-10f1a4173ca8.bin")
        assertTrue("Expected >= 2048 samples, got ${samples.size}", samples.size >= 2048)

        val engine = WelchPsdEngine()
        val params = WelchPsdParameters(fftSize = 2048, overlapPercentage = 0.5f)
        val sampleRateHz = 100f

        val result = engine.calculateMultiAxisWelchPsd(samples, sampleRateHz, params)

        val psd = result.psdMagnitude.powerSpectralDensity
        val df = result.output.effectiveDeltaFHz.toDouble()
        var integratedPower = 0.0
        for (p in psd) integratedPower += p * df

        val settlingN = result.output.settlingWindow?.settlingDurationSamples ?: 0
        val gravResult = engine.removeGravityAndDetrend(samples)
        val gfSamples = gravResult.gravityFreeSamples
        val usableN = samples.size - settlingN

        val rawX = FloatArray(usableN) { gfSamples[it + settlingN].x }
        val rawY = FloatArray(usableN) { gfSamples[it + settlingN].y }
        val rawZ = FloatArray(usableN) { gfSamples[it + settlingN].z }

        val filtX = engine.highPassFilter(rawX, sampleRateHz, 0.5f)
        val filtY = engine.highPassFilter(rawY, sampleRateHz, 0.5f)
        val filtZ = engine.highPassFilter(rawZ, sampleRateHz, 0.5f)

        var timeSumSq = 0.0
        for (i in filtX.indices) {
            val x = filtX[i].toDouble()
            val y = filtY[i].toDouble()
            val z = filtZ[i].toDouble()
            timeSumSq += x * x + y * y + z * z
        }
        val timeVariance = timeSumSq / filtX.size

        val overallRatio = sqrt(timeVariance) / sqrt(integratedPower)
        println("Fixture 2 Parseval: Ratio=$overallRatio")

        assertEquals(
            "Parseval mismatch on second fixture: time/PSD ratio should be ~1.0",
            1.0, overallRatio, 0.05
        )
    }


}

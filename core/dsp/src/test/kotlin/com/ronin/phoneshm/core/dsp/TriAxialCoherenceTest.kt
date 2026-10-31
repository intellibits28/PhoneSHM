package com.ronin.phoneshm.core.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class TriAxialCoherenceTest {

    @Test
    fun testAxisAlignedSwayPassesGate() {
        val sampleRateHz = 100.0f
        val n = 4096
        val dtNs = 10_000_000L
        val rng = Random(42)

        // Case A: Pure 1D horizontal sway along X-axis at f0 = 2.5 Hz (gamma_xy^2 ~ 0)
        val f0X = 2.5
        val samples1D = ArrayList<GravityFreeSample>(n)
        for (i in 0 until n) {
            val t = i.toDouble() / sampleRateHz
            val swayX = 0.08 * sin(2.0 * PI * f0X * t) + (rng.nextDouble() - 0.5) * 0.002
            val noiseY = (rng.nextDouble() - 0.5) * 0.002
            val noiseZ = (rng.nextDouble() - 0.5) * 0.002
            samples1D.add(
                GravityFreeSample(
                    timestampNs = 1_000_000_000L + i * dtNs,
                    x = swayX.toFloat(),
                    y = noiseY.toFloat(),
                    z = noiseZ.toFloat()
                )
            )
        }

        val efddEngine: EfddEngine = DefaultEfddEngine()
        val result1D = efddEngine.calculateFdd(samples1D, sampleRateHz, fftSize = 1024, overlapPct = 0.5f)
        val mode1D = result1D.modes.minByOrNull { abs(it.frequencyHz - f0X.toFloat()) }
        assertNotNull("Expected 2.5 Hz mode to be detected", mode1D)
        mode1D!!

        assertEquals(2.5f, mode1D.frequencyHz, 0.15f)
        assertTrue("Expected low X-Y coherence for pure 1D X-axis sway, got ${mode1D.coherenceXY}", mode1D.coherenceXY < 0.30f)
        assertTrue("Expected high H/V ratio (> 3.0), got ${mode1D.hvRatio}", mode1D.hvRatio > 3.0f)
        assertTrue("Expected low X-Z coherence (<= 0.35), got ${mode1D.coherenceXZ}", mode1D.coherenceXZ <= 0.35f)
        assertTrue("Expected low Y-Z coherence (<= 0.35), got ${mode1D.coherenceYZ}", mode1D.coherenceYZ <= 0.35f)
        assertTrue("Pure 1D X-axis sway must pass Axis-Aligned Sway Gate", mode1D.isValidHorizontalSway)
        assertEquals("Expected azimuth near 0 deg for X-axis sway", 0.0f, abs(mode1D.azimuthDegrees), 5.0f)

        // Case B: Diagonal horizontal sway at theta = 35 degrees at f0 = 3.125 Hz
        val f0Diag = 3.125
        val targetAzimuthDeg = 35.0
        val azRad = targetAzimuthDeg * PI / 180.0
        val samplesDiag = ArrayList<GravityFreeSample>(n)
        for (i in 0 until n) {
            val t = i.toDouble() / sampleRateHz
            val modalDisp = 0.10 * sin(2.0 * PI * f0Diag * t)
            val vx = modalDisp * cos(azRad) + (rng.nextDouble() - 0.5) * 0.001
            val vy = modalDisp * sin(azRad) + (rng.nextDouble() - 0.5) * 0.001
            val vz = (rng.nextDouble() - 0.5) * 0.002
            samplesDiag.add(
                GravityFreeSample(
                    timestampNs = 1_000_000_000L + i * dtNs,
                    x = vx.toFloat(),
                    y = vy.toFloat(),
                    z = vz.toFloat()
                )
            )
        }

        val resultDiag = efddEngine.calculateFdd(samplesDiag, sampleRateHz, fftSize = 1024, overlapPct = 0.5f)
        val modeDiag = resultDiag.modes.minByOrNull { abs(it.frequencyHz - f0Diag.toFloat()) }
        assertNotNull("Expected 3.125 Hz diagonal mode to be detected", modeDiag)
        modeDiag!!

        assertTrue("Expected diagonal mode to pass sway gate", modeDiag.isValidHorizontalSway)
        assertTrue("Expected high X-Y coherence for diagonal sway, got ${modeDiag.coherenceXY}", modeDiag.coherenceXY > 0.85f)
        assertEquals("Expected SVD azimuth ~ 35 deg", targetAzimuthDeg.toFloat(), modeDiag.azimuthDegrees, 3.0f)
    }

    @Test
    fun testVerticalSlabBounceRejected() {
        val sampleRateHz = 100.0f
        val n = 4096
        val dtNs = 10_000_000L
        val rng = Random(99)

        // Dominant vertical Z-axis vibration at 5.0 Hz coupled weakly into X
        val fSlab = 5.0
        val samples = ArrayList<GravityFreeSample>(n)
        for (i in 0 until n) {
            val t = i.toDouble() / sampleRateHz
            val verticalBounce = 0.15 * sin(2.0 * PI * fSlab * t)
            val coupledX = 0.04 * sin(2.0 * PI * fSlab * t) + (rng.nextDouble() - 0.5) * 0.001
            val noiseY = (rng.nextDouble() - 0.5) * 0.001
            val vz = verticalBounce + (rng.nextDouble() - 0.5) * 0.001
            samples.add(
                GravityFreeSample(
                    timestampNs = 1_000_000_000L + i * dtNs,
                    x = coupledX.toFloat(),
                    y = noiseY.toFloat(),
                    z = vz.toFloat()
                )
            )
        }

        val efddEngine: EfddEngine = DefaultEfddEngine()
        val result = efddEngine.calculateFdd(samples, sampleRateHz, fftSize = 1024, overlapPct = 0.5f)
        val slabMode = result.modes.minByOrNull { abs(it.frequencyHz - fSlab.toFloat()) }
        assertNotNull("Expected 5.0 Hz peak in horizontal SVD", slabMode)
        slabMode!!

        assertTrue("Expected H/V ratio < 1.0 for vertical slab bounce, got ${slabMode.hvRatio}", slabMode.hvRatio < 1.0f)
        assertTrue("Expected high X-Z coherence (> 0.60) for coupled slab bounce, got ${slabMode.coherenceXZ}", slabMode.coherenceXZ > 0.60f)
        assertFalse("Vertical slab bounce must be rejected by horizontal sway gate", slabMode.isValidHorizontalSway)
    }

    @Test
    fun testZeroSpectrumDivisionByZeroGuard() {
        val sampleRateHz = 100.0f
        val n = 2048
        val dtNs = 10_000_000L

        // Pure zero dynamic signal
        val samples = List(n) { i ->
            GravityFreeSample(
                timestampNs = 1_000_000_000L + i * dtNs,
                x = 0.0f,
                y = 0.0f,
                z = 0.0f
            )
        }

        val efddEngine: EfddEngine = DefaultEfddEngine()
        val result = efddEngine.calculateFdd(samples, sampleRateHz, fftSize = 512, overlapPct = 0.5f)

        assertTrue("Expected non-empty frequency bins", result.frequencies.isNotEmpty())
        assertEquals(result.frequencies.size, result.coherenceXY.size)
        assertEquals(result.frequencies.size, result.coherenceXZ.size)
        assertEquals(result.frequencies.size, result.coherenceYZ.size)
        assertEquals(result.frequencies.size, result.hvRatio.size)

        for (i in result.frequencies.indices) {
            assertFalse("coherenceXY[$i] must not be NaN/Inf", result.coherenceXY[i].isNaN() || result.coherenceXY[i].isInfinite())
            assertFalse("coherenceXZ[$i] must not be NaN/Inf", result.coherenceXZ[i].isNaN() || result.coherenceXZ[i].isInfinite())
            assertFalse("coherenceYZ[$i] must not be NaN/Inf", result.coherenceYZ[i].isNaN() || result.coherenceYZ[i].isInfinite())
            assertFalse("hvRatio[$i] must not be NaN/Inf", result.hvRatio[i].isNaN() || result.hvRatio[i].isInfinite())

            assertEquals(0.0f, result.coherenceXY[i], 1e-6f)
            assertEquals(0.0f, result.coherenceXZ[i], 1e-6f)
            assertEquals(0.0f, result.coherenceYZ[i], 1e-6f)
            assertEquals(0.0f, result.hvRatio[i], 1e-6f)
        }
    }
}

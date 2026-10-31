package com.ronin.phoneshm.core.dsp

import com.ronin.phoneshm.core.sensor.AccelerationSample
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Random

class ParsevalTest {
    @Test
    fun testParsevalTheorem() {
        val engine = WelchPsdEngine()
        val fs = 100f
        val duration = 66
        val n = (fs * duration).toInt()
        val rnd = Random(42)
        
        var sumSq = 0.0
        val samples = (0 until n).map {
            val nx = (rnd.nextGaussian() * 0.05).toFloat()
            val ny = (rnd.nextGaussian() * 0.05).toFloat()
            val nz = (rnd.nextGaussian() * 0.05).toFloat()
            sumSq += nx*nx + ny*ny + nz*nz
            AccelerationSample(it * 10_000_000L, nx, ny, nz)
        }
        val expectedVar = (sumSq / n).toDouble()
        
        val params = WelchPsdParameters(fftSize = 2048, overlapPercentage = 0.5f)
        val result = engine.calculateMultiAxisWelchPsd(samples, fs, params)
        
        val psd = result.psdMagnitude.powerSpectralDensity
        val deltaF = result.output.effectiveDeltaFHz
        
        var integratedPower = 0.0
        for (p in psd) {
            integratedPower += p * deltaF
        }
        
        println("Expected Variance: $expectedVar")
        println("Integrated Power: $integratedPower")
        println("Ratio: ${expectedVar / integratedPower}")
        
        assertEquals(expectedVar, integratedPower, expectedVar * 0.20)
    }

    @Test
    fun testParsevalTheoremWithJitterAndResampling() {
        val engine = WelchPsdEngine()
        val fs = 100f
        val duration = 66
        val n = (fs * duration).toInt()
        val rnd = Random(12345)
        
        var currentTs = 0L
        val jitterySamples = mutableListOf<AccelerationSample>()
        for (i in 0 until n) {
            val nx = (rnd.nextGaussian() * 0.05).toFloat()
            val ny = (rnd.nextGaussian() * 0.05).toFloat()
            val nz = (rnd.nextGaussian() * 0.05).toFloat()
            // Sampling interval 10ms +/- 2ms jitter
            val jitterNs = (rnd.nextDouble() * 4_000_000L - 2_000_000L).toLong()
            currentTs += (10_000_000L + jitterNs)
            jitterySamples.add(AccelerationSample(currentTs, nx, ny, nz))
        }

        val resampledOutput = engine.resampleToUniformGrid(jitterySamples, fs, InterpolationMethod.PCHIP_CUBIC)
        val resampled = resampledOutput.samples

        var sumSq = 0.0
        for (s in resampled) {
            sumSq += s.x * s.x + s.y * s.y + s.z * s.z
        }
        val expectedVar = sumSq / resampled.size

        val params = WelchPsdParameters(fftSize = 2048, overlapPercentage = 0.5f)
        val result = engine.calculateMultiAxisWelchPsd(resampled, fs, params, gapMask = resampledOutput.gapMask)

        val psd = result.psdMagnitude.powerSpectralDensity
        val deltaF = result.output.effectiveDeltaFHz

        var integratedPower = 0.0
        for (p in psd) {
            integratedPower += p * deltaF
        }

        assertEquals(expectedVar, integratedPower, expectedVar * 0.25)
    }
}

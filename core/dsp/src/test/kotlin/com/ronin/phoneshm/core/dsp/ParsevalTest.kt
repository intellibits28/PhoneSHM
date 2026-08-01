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
}

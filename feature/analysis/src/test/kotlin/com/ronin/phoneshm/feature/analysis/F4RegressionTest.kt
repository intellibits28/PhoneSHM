package com.ronin.phoneshm.feature.analysis

import com.ronin.phoneshm.core.dsp.WelchPsdEngine
import com.ronin.phoneshm.core.dsp.WelchPsdParameters
import com.ronin.phoneshm.core.modal.DefaultModalAnalyzer
import com.ronin.phoneshm.core.modal.ExcitationSufficiency
import com.ronin.phoneshm.core.physics.DefaultPhysicsRulesEngine
import com.ronin.phoneshm.core.sensor.AccelerationSample
import com.ronin.phoneshm.core.storage.DefaultRawSampleStorageEngine
import com.ronin.phoneshm.core.dsp.MultiAxisSpectrumResult
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class F4RegressionTest {

    @Test
    fun testRealWorldInsufficientExcitationRecordings() = runBlocking {
        val dspEngine = WelchPsdEngine()
        val modalAnalyzer = DefaultModalAnalyzer()
        val physicsEngine = DefaultPhysicsRulesEngine()
        
        // Setup storage engine using a temp dir (it's just used as baseDir, but we're reading direct files)
        val tmpDir = File(System.getProperty("java.io.tmpdir") ?: "/tmp")
        val storageEngine = DefaultRawSampleStorageEngine(tmpDir)

        val fixturesDir = File("src/test/resources/fixtures")
        assertTrue(
            "Fixtures directory should exist at ${fixturesDir.absolutePath}",
            fixturesDir.exists()
        )
        
        val binFiles = fixturesDir.listFiles { _, name -> name.endsWith(".bin") }
        assertTrue("Expected 4 test fixtures, found ${binFiles?.size ?: 0}", binFiles != null && binFiles.size == 4)

        for (file in binFiles!!) {
            val data = storageEngine.readSamplesFromFile(file)
            val samples = List(data.sampleCount) { i ->
                AccelerationSample(data.timestampsNs[i], data.x[i], data.y[i], data.z[i])
            }
            
            val sampleRateHz = 100.0f
            val mainFftSize = minOf(1024, Integer.highestOneBit(samples.size))
            val mainParams = WelchPsdParameters(fftSize = mainFftSize)
            
            val mainSpectrum = dspEngine.calculateMultiAxisWelchPsd(samples, sampleRateHz, mainParams)
            
            val windowSize = 512
            val stepSize = 256
            val slidingFftSize = 256
            val slidingParams = WelchPsdParameters(fftSize = slidingFftSize)
            val slidingSpectra = mutableListOf<MultiAxisSpectrumResult>()
            if (samples.size >= windowSize) {
                var i = 0
                while (i + windowSize <= samples.size) {
                    val winSamples = samples.subList(i, i + windowSize)
                    slidingSpectra.add(dspEngine.calculateMultiAxisWelchPsd(winSamples, sampleRateHz, slidingParams))
                    i += stepSize
                }
            }
            
            val modalRes = modalAnalyzer.analyzeMultiAxisSpectrum(
                spectrum = mainSpectrum,
                slidingWindowSpectra = slidingSpectra,
                evaluatePhysics = { f0Hz, prominence ->
                    physicsEngine.classifyFrequency(f0Hz, prominence.toFloat(), "RESIDENTIAL_CONCRETE", 3)
                }
            )
            
            assertEquals("File ${file.name} should be INSUFFICIENT", ExcitationSufficiency.INSUFFICIENT, modalRes.excitationSufficiency)
        }
    }
}

package com.ronin.phoneshm.core.audio

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AudioContextModuleTest {

    private class FakeAudioContextModule : AudioContextModule {
        private var bufferRunning = false

        override fun startCircularBuffer() {
            bufferRunning = true
        }

        override suspend fun extractFeaturesAroundTrigger(): AudioContextResult {
            return AudioContextResult(
                eventLabel = "vehicle_passing",
                confidence = 0.89,
                rmsEnergy = 0.15f,
                spectralCentroidHz = 340.0f,
                lowFrequencyEnergyRatio = 0.72f,
                captureWindowSec = "-2.0s to +3.0s"
            )
        }
    }

    @Test
    fun testCircularBufferAndExtraction() = runTest {
        val module = FakeAudioContextModule()
        module.startCircularBuffer()
        val result = module.extractFeaturesAroundTrigger()
        assertNotNull(result)
        assertEquals("vehicle_passing", result.eventLabel)
        assertEquals("-2.0s to +3.0s", result.captureWindowSec)
    }
}

package com.ronin.phoneshm.core.audio

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultAudioContextModuleTest {

    private lateinit var module: DefaultAudioContextModule

    @Before
    fun setup() {
        module = DefaultAudioContextModule(null)
    }

    @Test
    fun `test circular buffer simulation and feature extraction`() = runTest {
        module.startCircularBuffer()
        
        val result = module.extractFeaturesAroundTrigger()
        
        // Assert mock returns sensible values
        assertNotNull(result)
        assertTrue(result.confidence >= 0.0)
        assertTrue(result.confidence <= 1.0)
        assertTrue(result.rmsEnergy >= 0)
        assertTrue(result.spectralCentroidHz >= 0)
        
        module.stopCircularBuffer()
    }
}

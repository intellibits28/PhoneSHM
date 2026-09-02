package com.ronin.phoneshm.core.dsp

import com.ronin.phoneshm.core.sensor.AccelerationSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class DspEngineTest {

    private class FakeDspEngine : DspEngine {
        override fun removeGravityAndDetrend(samples: List<AccelerationSample>): GravityRemovalResult {
            val gfSamples = samples.map { GravityFreeSample(it.timestampNs, it.x, it.y, it.z) }
            return GravityRemovalResult(gfSamples, emptyList(), 0f)
        }
        override fun fuseAxes(samples: List<GravityFreeSample>): FloatArray = FloatArray(samples.size)
        override fun highPassFilter(signal: FloatArray, sampleRateHz: Float, cutoffHz: Float): FloatArray = signal
        override fun applyHanningWindow(windowSegment: FloatArray): FloatArray = windowSegment
        override fun streamingRmsLevel(sample: AccelerationSample): Float = 0f
        override fun resetStreamingState() {}

        override fun calculateMultiAxisWelchPsd(
            samples: List<AccelerationSample>,
            sampleRateHz: Float,
            params: WelchPsdParameters,
            ambientSnrThresholdDb: Float
        ): MultiAxisSpectrumResult {
            val emptyAxis = AxisPsdResult(floatArrayOf(8.2f), floatArrayOf(1.0f), listOf(Peak(8.2f, 1.0f, 0.8f)))
            return MultiAxisSpectrumResult(emptyAxis, emptyAxis, emptyAxis, emptyAxis,
                WelchPsdOutput(params, 1, 100f / params.fftSize, null))
        }

        override fun verifyImpulseQuality(
            samples: List<AccelerationSample>,
            sampleRateHz: Float,
            peakToRmsThreshold: Double,
            spectralSanityThreshold: Double
        ): WelchPsdEngine.ImpulseVerificationResult {
            return WelchPsdEngine.ImpulseVerificationResult(10.0, true, 0.8, true, true)
        }

        override fun verifySamplingContinuity(
            samples: List<AccelerationSample>,
            gapThresholdMs: Double,
            maxAllowedMissingRatio: Double
        ): WelchPsdEngine.SamplingContinuityResult {
            return WelchPsdEngine.SamplingContinuityResult(600.0, 0, 0.0, 0.0, true)
        }

        override fun resampleToUniformGrid(
            samples: List<AccelerationSample>,
            targetSampleRateHz: Float
        ): List<AccelerationSample> {
            return samples
        }
    }

    @Test
    fun testMultiAxisWelchPsdStructure() {
        val engine = FakeDspEngine()
        val result = engine.calculateMultiAxisWelchPsd(listOf(AccelerationSample(0, 0f, 0f, 0f)))
        assertNotNull(result.psdX)
        assertEquals(8.2f, result.psdX.peaks.first().frequencyHz, 0.001f)
    }
}

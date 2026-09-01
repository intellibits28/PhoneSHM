package com.ronin.phoneshm.core.dsp

data class EfddModeResult(
    val frequencyHz: Float,
    val powerMagnitude: Float,
    val prominence: Float,
    val dampingRatio: Float
)

data class EfddSpectrumResult(
    val frequencies: FloatArray,
    val firstSingularValues: FloatArray,
    val modes: List<EfddModeResult>
)

interface EfddEngine {
    fun calculateFdd(
        samples: List<GravityFreeSample>,
        sampleRateHz: Float,
        fftSize: Int,
        overlapPct: Float = 0.5f
    ): EfddSpectrumResult
}

class DefaultEfddEngine : EfddEngine {
    override fun calculateFdd(
        samples: List<GravityFreeSample>,
        sampleRateHz: Float,
        fftSize: Int,
        overlapPct: Float
    ): EfddSpectrumResult {
        if (samples.isEmpty()) {
            return EfddSpectrumResult(FloatArray(0), FloatArray(0), emptyList())
        }

        val timestamps = LongArray(samples.size) { samples[it].timestampNs }
        val x = FloatArray(samples.size) { samples[it].x }
        val y = FloatArray(samples.size) { samples[it].y }
        val z = FloatArray(samples.size) { samples[it].z }

        val nativeResult = NativeDspBridge.nativeCalculateFdd(
            timestamps = timestamps,
            x = x,
            y = y,
            z = z,
            sampleRateHz = sampleRateHz,
            fftSize = fftSize,
            overlapPct = overlapPct
        )

        val modes = mutableListOf<EfddModeResult>()
        for (i in nativeResult.peakFrequencies.indices) {
            modes.add(
                EfddModeResult(
                    frequencyHz = nativeResult.peakFrequencies[i],
                    powerMagnitude = nativeResult.peakMagnitudes[i],
                    prominence = nativeResult.peakProminences[i],
                    dampingRatio = nativeResult.peakDampingRatios[i]
                )
            )
        }

        return EfddSpectrumResult(
            frequencies = nativeResult.frequencies,
            firstSingularValues = nativeResult.firstSingularValues,
            modes = modes
        )
    }
}

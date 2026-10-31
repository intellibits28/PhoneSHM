package com.ronin.phoneshm.core.dsp

data class EfddModeResult(
    val frequencyHz: Float,
    val powerMagnitude: Float,
    val prominence: Float,
    val dampingRatio: Float,
    val coherenceXY: Float = 0.0f,
    val coherenceXZ: Float = 0.0f,
    val coherenceYZ: Float = 0.0f,
    val hvRatio: Float = 0.0f,
    val azimuthDegrees: Float = 0.0f,
    val isValidHorizontalSway: Boolean = false
)

data class EfddSpectrumResult(
    val frequencies: FloatArray,
    val firstSingularValues: FloatArray,
    val modes: List<EfddModeResult>,
    val coherenceXY: FloatArray = FloatArray(0),
    val coherenceXZ: FloatArray = FloatArray(0),
    val coherenceYZ: FloatArray = FloatArray(0),
    val hvRatio: FloatArray = FloatArray(0)
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
                    dampingRatio = nativeResult.peakDampingRatios[i],
                    coherenceXY = nativeResult.peakCoherenceXY.getOrElse(i) { 0.0f },
                    coherenceXZ = nativeResult.peakCoherenceXZ.getOrElse(i) { 0.0f },
                    coherenceYZ = nativeResult.peakCoherenceYZ.getOrElse(i) { 0.0f },
                    hvRatio = nativeResult.peakHvRatios.getOrElse(i) { 0.0f },
                    azimuthDegrees = nativeResult.peakAzimuthDegrees.getOrElse(i) { 0.0f },
                    isValidHorizontalSway = nativeResult.peakIsValidHorizontalSway.getOrElse(i) { false }
                )
            )
        }

        return EfddSpectrumResult(
            frequencies = nativeResult.frequencies,
            firstSingularValues = nativeResult.firstSingularValues,
            modes = modes,
            coherenceXY = nativeResult.coherenceXY,
            coherenceXZ = nativeResult.coherenceXZ,
            coherenceYZ = nativeResult.coherenceYZ,
            hvRatio = nativeResult.hvRatio
        )
    }
}

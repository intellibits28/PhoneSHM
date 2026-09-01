package com.ronin.phoneshm.core.dsp

data class NativeWelchResult(
    val segmentCount: Int,
    val frequencies: FloatArray,
    val psdX: FloatArray,
    val psdY: FloatArray,
    val psdZ: FloatArray,
    val psdMagnitude: FloatArray
)

data class NativeFddResult(
    val frequencies: FloatArray,
    val firstSingularValues: FloatArray,
    val peakFrequencies: FloatArray,
    val peakMagnitudes: FloatArray,
    val peakProminences: FloatArray,
    val peakDampingRatios: FloatArray
)

object NativeDspBridge {
    init {
        try {
            System.loadLibrary("phoneshm_dsp")
        } catch (e: Throwable) {
            println("NativeDspBridge: Failed to load phoneshm_dsp (Expected during JVM Unit Tests)")
        }
    }

    external fun nativeCalculateFdd(
        timestamps: LongArray,
        x: FloatArray,
        y: FloatArray,
        z: FloatArray,
        sampleRateHz: Float,
        fftSize: Int,
        overlapPct: Float
    ): NativeFddResult

    external fun nativeWelchPsdSingleAxis(
        signal: FloatArray,
        fftSize: Int,
        overlapPct: Float,
        sampleRateHz: Float
    ): FloatArray

    external fun nativeCalculateMultiAxisWelchPsd(
        timestamps: LongArray,
        x: FloatArray,
        y: FloatArray,
        z: FloatArray,
        sampleRateHz: Float,
        fftSize: Int,
        overlapPct: Float
    ): NativeWelchResult

    external fun nativeStreamingRmsLevel(
        x: Float,
        y: Float,
        z: Float,
        prevRms: Float
    ): Float
}

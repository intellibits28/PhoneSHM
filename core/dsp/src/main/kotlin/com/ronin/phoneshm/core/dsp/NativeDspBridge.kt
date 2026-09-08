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

    external fun nativeCalculateFddRaw(
        timestamps: LongArray,
        x: FloatArray,
        y: FloatArray,
        z: FloatArray,
        sampleRateHz: Float,
        fftSize: Int,
        overlapPct: Float
    ): FloatArray

    fun nativeCalculateFdd(
        timestamps: LongArray,
        x: FloatArray,
        y: FloatArray,
        z: FloatArray,
        sampleRateHz: Float,
        fftSize: Int,
        overlapPct: Float
    ): NativeFddResult {
        val raw = nativeCalculateFddRaw(timestamps, x, y, z, sampleRateHz, fftSize, overlapPct)
        if (raw.size < 2) {
            return NativeFddResult(
                frequencies = FloatArray(0),
                firstSingularValues = FloatArray(0),
                peakFrequencies = FloatArray(0),
                peakMagnitudes = FloatArray(0),
                peakProminences = FloatArray(0),
                peakDampingRatios = FloatArray(0)
            )
        }

        val freqLen = raw[0].toInt()
        val peaksLen = raw[1].toInt()

        val frequencies = if (freqLen > 0 && 2 + freqLen <= raw.size) {
            raw.copyOfRange(2, 2 + freqLen)
        } else FloatArray(0)

        val sv = if (freqLen > 0 && 2 + 2 * freqLen <= raw.size) {
            raw.copyOfRange(2 + freqLen, 2 + 2 * freqLen)
        } else FloatArray(0)

        val pFreqs = FloatArray(peaksLen)
        val pMags = FloatArray(peaksLen)
        val pProms = FloatArray(peaksLen)
        val pDamps = FloatArray(peaksLen)

        var pOffset = 2 + 2 * freqLen
        for (i in 0 until peaksLen) {
            if (pOffset + 3 < raw.size) {
                pFreqs[i] = raw[pOffset]
                pMags[i] = raw[pOffset + 1]
                pProms[i] = raw[pOffset + 2]
                pDamps[i] = raw[pOffset + 3]
            }
            pOffset += 4
        }

        return NativeFddResult(
            frequencies = frequencies,
            firstSingularValues = sv,
            peakFrequencies = pFreqs,
            peakMagnitudes = pMags,
            peakProminences = pProms,
            peakDampingRatios = pDamps
        )
    }

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

    external fun nativeCalculateRdtSsi(
        x: FloatArray,
        y: FloatArray,
        z: FloatArray,
        sampleRateHz: Float,
        minHz: Float,
        maxHz: Float,
        maxModelOrder: Int,
        rdsDurationSec: Float
    ): FloatArray
}

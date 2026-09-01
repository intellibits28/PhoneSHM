package com.ronin.phoneshm.core.dsp

data class RdtSsiPole(
    val frequencyHz: Float,
    val dampingRatio: Float,
    val modelOrder: Int
)

data class RdtSsiResult(
    val poles: List<RdtSsiPole>
)

interface RdtSsiEngine {
    fun calculateSsi(
        timestamps: LongArray,
        x: FloatArray,
        y: FloatArray,
        z: FloatArray,
        sampleRateHz: Float,
        minHz: Float,
        maxHz: Float
    ): RdtSsiResult
}

class DefaultRdtSsiEngine : RdtSsiEngine {
    override fun calculateSsi(
        timestamps: LongArray,
        x: FloatArray,
        y: FloatArray,
        z: FloatArray,
        sampleRateHz: Float,
        minHz: Float,
        maxHz: Float
    ): RdtSsiResult {
        if (timestamps.isEmpty()) return RdtSsiResult(emptyList())

        // Pass down to C++ via JNI. Max order 40, rds duration 3.0 sec.
        val rawResult = NativeDspBridge.nativeCalculateRdtSsi(
            x, y, z, sampleRateHz, minHz, maxHz, 40, 3.0f
        )
        
        val poles = mutableListOf<RdtSsiPole>()
        var i = 0
        while (i + 2 < rawResult.size) {
            val freq = rawResult[i]
            val damp = rawResult[i + 1]
            val order = rawResult[i + 2].toInt()
            poles.add(RdtSsiPole(freq, damp, order))
            i += 3
        }
        
        return RdtSsiResult(poles)
    }
}

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
        
        val allPoles = mutableListOf<RdtSsiPole>()
        var i = 0
        while (i + 2 < rawResult.size) {
            val freq = rawResult[i]
            val damp = rawResult[i + 1]
            val order = rawResult[i + 2].toInt()
            allPoles.add(RdtSsiPole(freq, damp, order))
            i += 3
        }
        
        // Phase 3: SSI Advanced Validation - Stable Pole Clustering
        allPoles.sortBy { it.frequencyHz }
        
        val clusters = mutableListOf<List<RdtSsiPole>>()
        var currentCluster = mutableListOf<RdtSsiPole>()
        
        val freqTolerance = 0.015f // 1.5% frequency tolerance for clustering
        
        for (pole in allPoles) {
            if (currentCluster.isEmpty()) {
                currentCluster.add(pole)
            } else {
                val refFreq = currentCluster.map { it.frequencyHz }.average().toFloat()
                if (kotlin.math.abs(pole.frequencyHz - refFreq) / refFreq < freqTolerance) {
                    currentCluster.add(pole)
                } else {
                    clusters.add(currentCluster)
                    currentCluster = mutableListOf(pole)
                }
            }
        }
        if (currentCluster.isNotEmpty()) {
            clusters.add(currentCluster)
        }
        
        // A pole is "stable" if it appears in at least 4 different model orders
        val stablePoles = clusters
            .filter { cluster -> cluster.map { it.modelOrder }.distinct().size >= 4 }
            .map { cluster ->
                RdtSsiPole(
                    frequencyHz = cluster.map { it.frequencyHz }.average().toFloat(),
                    dampingRatio = cluster.map { it.dampingRatio }.average().toFloat(),
                    modelOrder = cluster.map { it.modelOrder }.distinct().size // Overload modelOrder as stability weight
                )
            }
            // Sort by stability weight (most stable first)
            .sortedByDescending { it.modelOrder }
        
        return RdtSsiResult(stablePoles)
    }
}

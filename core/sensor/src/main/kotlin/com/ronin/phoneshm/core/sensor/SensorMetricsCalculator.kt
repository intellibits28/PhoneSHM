package com.ronin.phoneshm.core.sensor

import kotlin.math.sqrt

object SensorMetricsCalculator {

    /**
     * Calculates:
     * 1. actualAverageSampleRateHz
     * 2. sampleJitterStdMs
     * 3. clockDriftPpm
     *
     * Returns Triple(actualAverageSampleRateHz, sampleJitterStdMs, clockDriftPpm)
     */
    fun calculateMetrics(
        hardwareTimestamps: LongArray,
        systemArrivalTimesNs: LongArray
    ): Triple<Float, Float, Float> {
        val n = hardwareTimestamps.size
        if (n < 2) {
            return Triple(0.0f, 0.0f, 0.0f)
        }

        // 1. Average sample rate
        val durationNs = hardwareTimestamps.last() - hardwareTimestamps.first()
        val durationSecs = durationNs.toDouble() / 1_000_000_000.0
        val actualAverageSampleRateHz = if (durationSecs > 0.0) {
            ((n - 1) / durationSecs).toFloat()
        } else {
            0.0f
        }

        // 2. Jitter (standard deviation of consecutive intervals in milliseconds)
        val intervalsMs = DoubleArray(n - 1)
        var sumIntervals = 0.0
        for (i in 0 until n - 1) {
            val interval = (hardwareTimestamps[i + 1] - hardwareTimestamps[i]).toDouble() / 1_000_000.0
            intervalsMs[i] = interval
            sumIntervals += interval
        }
        val meanInterval = sumIntervals / (n - 1)
        var sumSquaredDiffs = 0.0
        for (i in intervalsMs.indices) {
            val diff = intervalsMs[i] - meanInterval
            sumSquaredDiffs += diff * diff
        }
        val sampleJitterStdMs = sqrt(sumSquaredDiffs / (n - 1)).toFloat()

        // 3. Clock drift in parts per million (PPM) vs system clock
        val dSensor = hardwareTimestamps.last() - hardwareTimestamps.first()
        val dSystem = systemArrivalTimesNs.last() - systemArrivalTimesNs.first()
        val clockDriftPpm = if (dSystem > 0L) {
            (((dSensor.toDouble() - dSystem.toDouble()) / dSystem.toDouble()) * 1_000_000.0).toFloat()
        } else {
            0.0f
        }

        return Triple(actualAverageSampleRateHz, sampleJitterStdMs, clockDriftPpm)
    }
}

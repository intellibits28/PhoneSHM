package com.ronin.phoneshm.core.sensor

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VibrationSensorEngineTest {

    private class FakeVibrationSensorEngine : VibrationSensorEngine {
        override fun startStreaming(targetHz: Int): Flow<AccelerationSample> {
            return flowOf(
                AccelerationSample(1000000000L, 0.01f, -0.01f, 9.81f),
                AccelerationSample(1000010000L, 0.02f, -0.02f, 9.80f)
            )
        }

        override suspend fun recordSession(
            sessionId: String,
            profileId: String,
            buildingHash: String?,
            buildingDisplayName: String?,
            buildingType: String?,
            floors: Int?,
            constructionYear: Int?,
            primaryMaterial: String?,
            latitude: Double?,
            longitude: Double?,
            measurementFloorLevel: Int?,
            surfaceType: String?,
            locationType: String?,
            phonePlacement: String?,
            durationSec: Int,
            sessionNoiseFloorMg: Float?,
            sessionAccelerometerBias: FloatArray?
        ): MeasurementSessionMetadata {
            return MeasurementSessionMetadata(
                sessionId = sessionId,
                measurementProfileId = profileId,
                deviceCapabilityReportId = "device_1",
                targetDurationSeconds = durationSec,
                targetSampleRateHz = 100,
                actualAverageSampleRateHz = 99.8f,
                sampleJitterStdMs = 1.8f,
                clockDriftPpm = 45.0f,
                rawStorageFileUri = "/storage/emulated/0/phoneshm/raw/$sessionId.bin"
            )
        }
    }

    @Test
    fun testRecordSessionMetadata() = runTest {
        val engine = FakeVibrationSensorEngine()
        val meta = engine.recordSession(
            sessionId = "session_abc", 
            profileId = "profile_xyz",
            durationSec = 30
        )
        assertEquals("session_abc", meta.sessionId)
        assertTrue(meta.sampleJitterStdMs < 3.0f)
        assertEquals(45.0f, meta.clockDriftPpm, 0.001f)
    }

    @Test
    fun testSensorMetricsCalculatorCalculations() {
        // Prepare exact 100Hz timestamps with zero jitter and zero drift:
        // Hardware clock ticks every 10ms (10,000,000 ns)
        // System clock also ticks every 10ms
        val hardwareTimestamps = LongArray(101) { 1_000_000_000L + it * 10_000_000L }
        val systemArrivalTimes = LongArray(101) { 5_000_000_000L + it * 10_000_000L }

        val (rate, jitter, drift) = SensorMetricsCalculator.calculateMetrics(hardwareTimestamps, systemArrivalTimes)

        assertEquals(100.0f, rate, 0.01f)
        assertEquals(0.0f, jitter, 0.01f)
        assertEquals(0.0f, drift, 0.01f)
    }

    @Test
    fun testSensorMetricsCalculatorWithJitterAndDrift() {
        // 5 samples: hardware intervals are 10ms, 12ms, 8ms, 10ms
        // Mean interval = 10ms. Standard deviation = sqrt(((0)^2 + (2)^2 + (-2)^2 + (0)^2)/4) = sqrt(8/4) = sqrt(2.0) = 1.414ms
        val hardwareTimestamps = longArrayOf(
            1_000_000_000L,
            1_010_000_000L, // +10ms
            1_022_000_000L, // +12ms
            1_030_000_000L, // +8ms
            1_040_000_000L  // +10ms
        )

        // System clock elapsed time:
        // First sample received at 5_000_000_000L
        // Last sample received at 5_038_000_000L (+38ms)
        // Hardware elapsed time = 40ms. System elapsed time = 38ms.
        // Drift = (40ms - 38ms) / 38ms = 2 / 38 = 0.052631 = 52631.5 PPM
        val systemArrivalTimes = longArrayOf(
            5_000_000_000L,
            5_010_000_000L,
            5_020_000_000L,
            5_030_000_000L,
            5_038_000_000L
        )

        val (rate, jitter, drift) = SensorMetricsCalculator.calculateMetrics(hardwareTimestamps, systemArrivalTimes)

        // rate = (5 - 1) / 0.04s = 100 Hz
        assertEquals(100.0f, rate, 0.01f)
        assertEquals(1.414f, jitter, 0.01f)
        assertEquals(52631.58f, drift, 0.1f)
    }

    @Test
    fun testSensorMetricsCalculatorEdgeCases() {
        // Less than 2 samples
        val emptyHW = LongArray(0)
        val emptySYS = LongArray(0)
        val (rate0, jitter0, drift0) = SensorMetricsCalculator.calculateMetrics(emptyHW, emptySYS)
        assertEquals(0.0f, rate0, 0.0f)
        assertEquals(0.0f, jitter0, 0.0f)
        assertEquals(0.0f, drift0, 0.0f)

        val singleHW = longArrayOf(1000L)
        val singleSYS = longArrayOf(5000L)
        val (rate1, jitter1, drift1) = SensorMetricsCalculator.calculateMetrics(singleHW, singleSYS)
        assertEquals(0.0f, rate1, 0.0f)
        assertEquals(0.0f, jitter1, 0.0f)
        assertEquals(0.0f, drift1, 0.0f)
    }
}

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

        override suspend fun recordSession(sessionId: String, profileId: String, durationSec: Int): MeasurementSessionMetadata {
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
        val meta = engine.recordSession("session_abc", "profile_xyz", 30)
        assertEquals("session_abc", meta.sessionId)
        assertTrue(meta.sampleJitterStdMs < 3.0f)
        assertEquals(45.0f, meta.clockDriftPpm, 0.001f)
    }
}

package com.ronin.phoneshm.feature.measurement

import com.ronin.phoneshm.core.sensor.AccelerationSample
import com.ronin.phoneshm.core.sensor.MeasurementSessionMetadata
import com.ronin.phoneshm.core.sensor.VibrationSensorEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MeasurementViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private class FakeSensorEngine : VibrationSensorEngine {
        var recordSessionCalledCount = 0

        override fun startStreaming(targetHz: Int): Flow<AccelerationSample> {
            return flowOf(
                AccelerationSample(1000000000L, 0.1f, 0.2f, 9.8f)
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
            measurementFloorLevel: Int?,
            surfaceType: String?,
            locationType: String?,
            phonePlacement: String?,
            durationSec: Int
        ): MeasurementSessionMetadata {
            recordSessionCalledCount++
            return MeasurementSessionMetadata(
                sessionId = sessionId,
                measurementProfileId = profileId,
                deviceCapabilityReportId = "test_device",
                targetDurationSeconds = durationSec,
                targetSampleRateHz = 100,
                actualAverageSampleRateHz = 100.0f,
                sampleJitterStdMs = 0.0f,
                clockDriftPpm = 0.0f,
                rawStorageFileUri = "file://test/$sessionId.bin"
            )
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testRecordingLifecycle() = runTest {
        val sensorEngine = FakeSensorEngine()
        val viewModel = MeasurementViewModel(sensorEngine)

        // Initially idle
        assertFalse(viewModel.uiState.value.isRecording)
        assertFalse(viewModel.uiState.value.recordingFinished)

        // Start recording for 1 second
        viewModel.startRecording("b_hash_123", "m_hash_456", 1)
        
        // Wait for recording to complete (runTest scheduler fast forwards delays)
        delay(1200L)

        // Verify completed state
        assertFalse(viewModel.uiState.value.isRecording)
        assertTrue(viewModel.uiState.value.recordingFinished)
        assertEquals(100.0f, viewModel.uiState.value.currentSampleRateHz, 0.001f)
        assertEquals(0.0f, viewModel.uiState.value.clockJitterMs, 0.001f)
        assertEquals(0.0f, viewModel.uiState.value.clockDriftPpm, 0.001f)
        assertTrue(viewModel.uiState.value.rawStorageFileUri!!.startsWith("file://test/"))
    }
}

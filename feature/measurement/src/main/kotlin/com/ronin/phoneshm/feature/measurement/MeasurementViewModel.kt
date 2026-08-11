package com.ronin.phoneshm.feature.measurement

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ronin.phoneshm.core.database.repository.ProfileRepository
import com.ronin.phoneshm.core.sensor.VibrationSensorEngine
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MeasurementUiState(
    val isCalibrating: Boolean = false,
    val calibrationStatus: String = "",
    val isRecording: Boolean = false,
    val elapsedSeconds: Int = 0,
    val currentSampleRateHz: Float = 0f,
    val clockJitterMs: Float = 0f,
    val clockDriftPpm: Float = 0f,
    val rawStorageFileUri: String? = null,
    val currentX: Float = 0f,
    val currentY: Float = 0f,
    val currentZ: Float = 0f,
    val totalSamplesCollected: Int = 0,
    val recordingFinished: Boolean = false,
    val hasPastSessions: Boolean = false
)

/**
 * MeasurementViewModel controls live sensor recording HUD, circular buffer extraction, and state.
 */
class MeasurementViewModel(
    private val sensorEngine: VibrationSensorEngine,
    private val profileRepository: ProfileRepository? = null,
    private val deviceCapabilityEngine: com.ronin.phoneshm.core.device.DeviceCapabilityEngine? = null
) : ViewModel() {
    private val _uiState = MutableStateFlow(MeasurementUiState())
    val uiState: StateFlow<MeasurementUiState> = _uiState.asStateFlow()

    private var recordingJob: Job? = null
    private var streamingJob: Job? = null

    fun checkPastSessions(measurementProfileId: String) {
        viewModelScope.launch {
            val hasSessions = profileRepository?.hasAnyRecordingForMeasurementProfile(measurementProfileId) ?: false
            _uiState.value = _uiState.value.copy(hasPastSessions = hasSessions)
        }
    }

    fun startRecording(
        buildingHash: String,
        measurementId: String,
        durationSec: Int,
        startDelaySec: Int = 2,
        onSessionRecorded: ((sessionId: String, rawStorageFileUri: String) -> Unit)? = null
    ) {
        if (_uiState.value.isRecording || _uiState.value.isCalibrating) return

        val sessionId = UUID.randomUUID().toString()

        viewModelScope.launch {
            _uiState.value = MeasurementUiState(isCalibrating = true)
            for (i in startDelaySec downTo 1) {
                _uiState.value = _uiState.value.copy(calibrationStatus = "Place your phone, starting in ${i}s...")
                delay(1000L)
            }
            
            _uiState.value = _uiState.value.copy(calibrationStatus = "Calibrating sensor...")
            deviceCapabilityEngine?.runZeroVelocityCalibration(3)
            val calibratedReport = deviceCapabilityEngine?.inspectDeviceCapabilities()
            val noiseFloorMg = calibratedReport?.estimatedNoiseFloorMg
            val bias = calibratedReport?.accelerometerBias
            _uiState.value = MeasurementUiState(isRecording = true)

            // 1. Live stream updates (to update raw accelerometer visualizers and sample counts)
        streamingJob = viewModelScope.launch {
            var count = 0
            val startTime = System.currentTimeMillis()
            try {
                sensorEngine.startStreaming(100).collect { sample ->
                    count++
                    val elapsed = (System.currentTimeMillis() - startTime) / 1000f
                    val rate = if (elapsed > 0f) count / elapsed else 100f
                    _uiState.value = _uiState.value.copy(
                        currentX = sample.x,
                        currentY = sample.y,
                        currentZ = sample.z,
                        totalSamplesCollected = count,
                        currentSampleRateHz = rate
                    )
                }
            } catch (e: Exception) {
                // Handle stream errors
            }
        }

        // Timer for elapsed seconds
        viewModelScope.launch {
            while (_uiState.value.isRecording && _uiState.value.elapsedSeconds < durationSec) {
                delay(1000L)
                if (_uiState.value.isRecording) {
                    _uiState.value = _uiState.value.copy(
                        elapsedSeconds = _uiState.value.elapsedSeconds + 1
                    )
                }
            }
        }

        // 2. Continuous record session
        recordingJob = launch {
            try {
                val buildingProfile = profileRepository?.getBuildingProfile(buildingHash)
                val measurementProfile = profileRepository?.getMeasurementProfile(measurementId)
                val displayName = buildingProfile?.displayName

                val metadata = sensorEngine.recordSession(
                    sessionId = sessionId, 
                    profileId = measurementId, 
                    buildingHash = buildingHash,
                    buildingDisplayName = displayName,
                    buildingType = buildingProfile?.buildingType,
                    floors = buildingProfile?.floors,
                    constructionYear = buildingProfile?.constructionYear,
                    primaryMaterial = buildingProfile?.material,
                    latitude = buildingProfile?.latitude,
                    longitude = buildingProfile?.longitude,
                    measurementFloorLevel = measurementProfile?.floorLevel,
                    surfaceType = measurementProfile?.surfaceType,
                    locationType = measurementProfile?.locationType,
                    phonePlacement = measurementProfile?.placement,
                    durationSec = durationSec,
                    sessionNoiseFloorMg = noiseFloorMg,
                    sessionAccelerometerBias = bias
                )
                streamingJob?.cancel()
                _uiState.value = _uiState.value.copy(
                    isRecording = false,
                    recordingFinished = true,
                    currentSampleRateHz = metadata.actualAverageSampleRateHz,
                    clockJitterMs = metadata.sampleJitterStdMs,
                    clockDriftPpm = metadata.clockDriftPpm,
                    rawStorageFileUri = metadata.rawStorageFileUri
                )
                onSessionRecorded?.invoke(metadata.sessionId, metadata.rawStorageFileUri)
            } catch (e: Exception) {
                streamingJob?.cancel()
                _uiState.value = _uiState.value.copy(
                    isRecording = false,
                    rawStorageFileUri = "Error: ${e.message}"
                )
            }
            }
        }
    }

    fun stopRecordingEarly() {
        recordingJob?.cancel()
        streamingJob?.cancel()
        _uiState.value = _uiState.value.copy(
            isRecording = false
        )
    }

    override fun onCleared() {
        super.onCleared()
        stopRecordingEarly()
    }
}

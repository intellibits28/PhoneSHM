package com.ronin.phoneshm.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ronin.phoneshm.core.database.model.BuildingProfile
import com.ronin.phoneshm.core.database.model.MeasurementProfile
import com.ronin.phoneshm.core.database.repository.ProfileRepository
import com.ronin.phoneshm.core.device.DeviceCapabilityEngine
import com.ronin.phoneshm.core.device.DeviceCapabilityReport
import com.ronin.phoneshm.core.device.SensorQualityTier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class OnboardingState(
    val step: Int = 1,
    val isInspectingDevice: Boolean = false,
    val deviceReport: DeviceCapabilityReport? = null,
    val isDeviceCalibrated: Boolean = false,
    val calibrationBias: FloatArray = floatArrayOf(0f, 0f, 0f),

    // Step 2: Building Typology
    val buildingName: String = "",
    val buildingType: String = "RESIDENTIAL_CONCRETE",
    val floors: String = "4",
    val constructionYear: String = "2020",
    val material: String = "Reinforced Concrete",

    // Step 3: Measurement Placement Setup
    val floorLevel: String = "1",
    val surfaceType: String = "CONCRETE",
    val locationType: String = "CENTER_SPAN",
    val placement: String = "FLAT_ON_FLOOR",

    // Status & Output
    val isSaving: Boolean = false,
    val isCompleted: Boolean = false,
    val savedBuildingId: String? = null,
    val savedMeasurementId: String? = null,
    val errorMessage: String? = null
)

/**
 * OnboardingViewModel manages startup wizard state, hardware capability evaluation, and profile persistence.
 */
class OnboardingViewModel(
    private val profileRepository: ProfileRepository? = null,
    private val deviceCapabilityEngine: DeviceCapabilityEngine? = null
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    fun inspectDevice() {
        if (deviceCapabilityEngine == null) {
            // Mock or default fallback if engine not injected
            _state.value = _state.value.copy(
                deviceReport = DeviceCapabilityReport(
                    deviceModel = "Android PhoneSHM Device",
                    sensorVendor = "High-Precision 100Hz Sensor",
                    maxSupportedSampleRateHz = 200,
                    estimatedNoiseFloorMg = 0.5f,
                    accelerometerBias = floatArrayOf(0.01f, -0.02f, 0.005f),
                    qualityTier = SensorQualityTier.RESEARCH_GRADE
                )
            )
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(isInspectingDevice = true)
            try {
                val report = deviceCapabilityEngine.inspectDeviceCapabilities()
                _state.value = _state.value.copy(isInspectingDevice = false, deviceReport = report)
            } catch (e: Exception) {
                _state.value = _state.value.copy(isInspectingDevice = false, errorMessage = e.message)
            }
        }
    }

    fun runCalibration() {
        if (deviceCapabilityEngine == null) {
            _state.value = _state.value.copy(
                isDeviceCalibrated = true,
                calibrationBias = floatArrayOf(0.005f, -0.003f, 0.001f)
            )
            return
        }
        viewModelScope.launch {
            try {
                val bias = deviceCapabilityEngine.runZeroVelocityCalibration(3)
                _state.value = _state.value.copy(isDeviceCalibrated = true, calibrationBias = bias)
            } catch (e: Exception) {
                _state.value = _state.value.copy(errorMessage = "Calibration failed: ${e.message}")
            }
        }
    }

    fun updateBuildingName(name: String) {
        _state.value = _state.value.copy(buildingName = name)
    }

    fun updateBuildingType(type: String) {
        _state.value = _state.value.copy(buildingType = type)
    }

    fun updateFloors(floors: String) {
        _state.value = _state.value.copy(floors = floors)
    }

    fun updateConstructionYear(year: String) {
        _state.value = _state.value.copy(constructionYear = year)
    }

    fun updateMaterial(material: String) {
        _state.value = _state.value.copy(material = material)
    }

    fun updateFloorLevel(level: String) {
        _state.value = _state.value.copy(floorLevel = level)
    }

    fun updateSurfaceType(surface: String) {
        _state.value = _state.value.copy(surfaceType = surface)
    }

    fun updateLocationType(location: String) {
        _state.value = _state.value.copy(locationType = location)
    }

    fun updatePlacement(placement: String) {
        _state.value = _state.value.copy(placement = placement)
    }

    fun setStep(newStep: Int) {
        _state.value = _state.value.copy(step = newStep)
    }

    fun saveProfileAndFinish() {
        val s = _state.value
        if (s.buildingName.isBlank()) {
            _state.value = s.copy(errorMessage = "Building name is required")
            return
        }

        val bId = "b_" + UUID.randomUUID().toString().substring(0, 8)
        val mId = "m_" + UUID.randomUUID().toString().substring(0, 8)

        val buildingProfile = BuildingProfile(
            id = bId,
            name = s.buildingName,
            type = s.buildingType,
            floors = s.floors.toIntOrNull() ?: 1,
            constructionYear = s.constructionYear.toIntOrNull() ?: 2020,
            material = s.material,
            buildingHash = "crowd_" + bId
        )

        val measurementProfile = MeasurementProfile(
            id = mId,
            buildingId = bId,
            floorLevel = s.floorLevel.toIntOrNull() ?: 1,
            surfaceType = s.surfaceType,
            locationType = s.locationType,
            placement = s.placement
        )

        if (profileRepository == null) {
            _state.value = s.copy(
                isCompleted = true,
                savedBuildingId = bId,
                savedMeasurementId = mId,
                step = 4
            )
            return
        }

        viewModelScope.launch {
            _state.value = _state.value.copy(isSaving = true, errorMessage = null)
            try {
                profileRepository.saveBuildingProfile(buildingProfile)
                profileRepository.saveMeasurementProfile(measurementProfile)
                _state.value = _state.value.copy(
                    isSaving = false,
                    isCompleted = true,
                    savedBuildingId = bId,
                    savedMeasurementId = mId,
                    step = 4
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(isSaving = false, errorMessage = "Save error: ${e.message}")
            }
        }
    }
}

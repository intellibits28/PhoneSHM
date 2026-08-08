package com.ronin.phoneshm.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ronin.phoneshm.core.database.model.BuildingProfile
import com.ronin.phoneshm.core.database.model.MeasurementProfile
import com.ronin.phoneshm.core.database.repository.ProfileRepository
import com.ronin.phoneshm.core.device.DeviceCapabilityEngine
import com.ronin.phoneshm.core.device.DeviceCapabilityReport
import com.ronin.phoneshm.core.device.SensorQualityTier
import com.ronin.phoneshm.core.location.LocationResolver
import com.ronin.phoneshm.core.location.PrivacyLevel
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

    val isEditMode: Boolean = false,
    val hasRecordedSessions: Boolean = false,

    // Step 2: Building Typology
    val buildingName: String = "",
    val buildingType: String = "RESIDENTIAL_CONCRETE",
    val floors: String = "4",
    val constructionYear: String = "2020",
    val material: String = "Reinforced Concrete",

    // Step 3: Location Privacy Setup
    val privacyLevel: PrivacyLevel = PrivacyLevel.APPROXIMATE_LOCATION,
    val resolvedLatitude: Double? = null,
    val resolvedLongitude: Double? = null,
    val resolvedBuildingHash: String? = null,

    // Step 4: Measurement Placement Setup
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
 * OnboardingViewModel manages startup wizard state, hardware capability evaluation,
 * privacy preferences, location resolution, and profile persistence.
 */
class OnboardingViewModel(
    private val profileRepository: ProfileRepository? = null,
    private val deviceCapabilityEngine: DeviceCapabilityEngine? = null,
    private val locationResolver: LocationResolver? = null
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    fun inspectDevice() {
        if (deviceCapabilityEngine == null) {
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

    fun updatePrivacyLevel(level: PrivacyLevel) {
        _state.value = _state.value.copy(privacyLevel = level)
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

    fun resolveCurrentLocation() {
        val resolver = locationResolver ?: return
        viewModelScope.launch {
            try {
                val profile = resolver.resolveLocation(_state.value.privacyLevel)
                profile?.let {
                    val hash = resolver.generateBuildingHash(it.latitude, it.longitude, _state.value.buildingName)
                    _state.value = _state.value.copy(
                        resolvedLatitude = it.latitude,
                        resolvedLongitude = it.longitude,
                        resolvedBuildingHash = hash
                    )
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(errorMessage = "Location resolution failed: ${e.message}")
            }
        }
    }

    fun adjustCoordinates(latOffset: Double, lonOffset: Double) {
        val s = _state.value
        val currentLat = s.resolvedLatitude ?: return
        val currentLon = s.resolvedLongitude ?: return
        val newLat = currentLat + latOffset
        val newLon = currentLon + lonOffset
        val finalHash = locationResolver?.generateBuildingHash(newLat, newLon, s.buildingName) ?: s.resolvedBuildingHash
        _state.value = _state.value.copy(
            resolvedLatitude = newLat,
            resolvedLongitude = newLon,
            resolvedBuildingHash = finalHash
        )
    }


    fun saveProfileAndFinish() {
        val s = _state.value
        if (s.buildingName.isBlank()) {
            _state.value = s.copy(errorMessage = "Building name is required")
            return
        }

        val bId = s.savedBuildingId ?: ("b_" + UUID.randomUUID().toString().substring(0, 8))
        val mId = s.savedMeasurementId ?: ("m_" + UUID.randomUUID().toString().substring(0, 8))

        viewModelScope.launch {
            _state.value = _state.value.copy(isSaving = true, errorMessage = null)
            try {
                var finalHash = s.resolvedBuildingHash
                if (finalHash == null && locationResolver != null) {
                    val loc = locationResolver.resolveLocation(s.privacyLevel)
                    if (loc != null) {
                        finalHash = locationResolver.generateBuildingHash(loc.latitude, loc.longitude, s.buildingName)
                    }
                }

                // Use the originally saved hash if we are in edit mode, so we update the same row
                val hashToUse = if (s.isEditMode && s.savedBuildingId != null) s.savedBuildingId else (finalHash ?: "crowd_anonymized_$bId")
                val buildingProfile = BuildingProfile(
                    buildingHash = hashToUse,
                    displayName = s.buildingName,
                    buildingType = s.buildingType,
                    floors = s.floors.toIntOrNull() ?: 1,
                    constructionYear = s.constructionYear.toIntOrNull() ?: 2020,
                    material = s.material
                )

                val measurementProfile = MeasurementProfile(
                    id = mId,
                    buildingId = hashToUse,
                    floorLevel = s.floorLevel.toIntOrNull() ?: 1,
                    surfaceType = s.surfaceType,
                    locationType = s.locationType,
                    placement = s.placement
                )

                if (profileRepository != null) {
                    profileRepository.saveBuildingProfile(buildingProfile)
                    profileRepository.saveMeasurementProfile(measurementProfile)
                }

                _state.value = _state.value.copy(
                    isSaving = false,
                    isCompleted = true,
                    savedBuildingId = hashToUse, // the hash is the primary identifier now
                    savedMeasurementId = mId,
                    step = 5
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(isSaving = false, errorMessage = "Save error: ${e.message}")
            }
        }
    }

    suspend fun checkBuildingExists(hash: String): Boolean {
        return profileRepository?.getBuildingProfile(hash) != null
    }

    fun getAllBuildingProfiles(): kotlinx.coroutines.flow.Flow<List<com.ronin.phoneshm.core.database.model.BuildingProfile>> {
        return profileRepository?.getAllBuildingProfiles() ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }

    fun loadProfileForEditing(hash: String) {
        viewModelScope.launch {
            val building = profileRepository?.getBuildingProfile(hash) ?: return@launch
            val hasSessions = profileRepository.hasAnyRecordingForBuilding(hash)
            
            _state.value = OnboardingState(
                step = if (hasSessions) 5 else 1,
                isEditMode = true,
                hasRecordedSessions = hasSessions,
                buildingName = building.displayName,
                buildingType = building.buildingType,
                floors = building.floors?.toString() ?: "4",
                constructionYear = building.constructionYear?.toString() ?: "2020",
                material = building.material ?: "Unknown",
                resolvedBuildingHash = building.buildingHash,
                savedBuildingId = building.buildingHash // preserve original hash
            )
            // Note: In a complete implementation we'd also load the measurement profile,
            // but the simplified workflow keeps it to the building primarily.
        }
    }

    fun resetState() {
        _state.value = OnboardingState(step = 1)
    }

    fun deleteBuildingProfile(hash: String, onDeleted: () -> Unit) {
        viewModelScope.launch {
            profileRepository?.deleteBuildingAndRelatedData(hash)
            
            // TASK 3: create deletionRequests document in Firestore
            try {
                val auth = com.google.firebase.auth.FirebaseAuth.getInstance()
                val uid = auth.currentUser?.uid
                if (uid != null) {
                    val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                    val requestPayload = hashMapOf(
                        "buildingHash" to hash,
                        "requestedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                        "firebaseAuthUid" to uid
                    )
                    db.collection("deletionRequests").add(requestPayload).addOnSuccessListener {
                        android.util.Log.d("PhoneSHM", "Deletion request queued in Firestore for building: $hash")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("PhoneSHM", "Failed to queue deletion request in Firestore", e)
            }
            
            onDeleted()
        }
    }
}

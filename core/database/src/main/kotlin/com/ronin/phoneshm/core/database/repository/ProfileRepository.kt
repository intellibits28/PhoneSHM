package com.ronin.phoneshm.core.database.repository

import com.ronin.phoneshm.core.database.model.BuildingProfile
import com.ronin.phoneshm.core.database.model.MeasurementProfile
import kotlinx.coroutines.flow.Flow

/**
 * ProfileRepository defines clean persistence operations for building and measurement profiles.
 */
interface ProfileRepository {
    suspend fun saveBuildingProfile(profile: BuildingProfile)
    suspend fun getBuildingProfile(buildingHash: String): BuildingProfile?
    fun getAllBuildingProfiles(): Flow<List<BuildingProfile>>

    suspend fun saveMeasurementProfile(profile: MeasurementProfile)
    suspend fun getMeasurementProfile(id: String): MeasurementProfile?
    suspend fun getMeasurementProfilesForBuilding(buildingHash: String): List<MeasurementProfile>

    suspend fun hasAnyRecordingForBuilding(buildingHash: String): Boolean
    suspend fun deleteBuildingAndRelatedData(buildingHash: String)
}

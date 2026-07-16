package com.ronin.phoneshm.core.database.repository

import com.ronin.phoneshm.core.database.model.BuildingProfile
import com.ronin.phoneshm.core.database.model.MeasurementProfile
import kotlinx.coroutines.flow.Flow

/**
 * ProfileRepository defines clean persistence operations for building and measurement profiles.
 */
interface ProfileRepository {
    suspend fun saveBuildingProfile(profile: BuildingProfile)
    suspend fun getBuildingProfile(id: String): BuildingProfile?
    fun getAllBuildingProfiles(): Flow<List<BuildingProfile>>

    suspend fun saveMeasurementProfile(profile: MeasurementProfile)
    suspend fun getMeasurementProfile(id: String): MeasurementProfile?
}

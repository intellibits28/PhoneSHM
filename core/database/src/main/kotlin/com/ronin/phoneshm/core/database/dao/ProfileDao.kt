package com.ronin.phoneshm.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ronin.phoneshm.core.database.entity.BuildingProfileEntity
import com.ronin.phoneshm.core.database.entity.MeasurementProfileEntity
import kotlinx.coroutines.flow.Flow

/**
 * ProfileDao manages transactional persistence for building and measurement configuration profiles.
 */
@Dao
interface ProfileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBuildingProfile(profile: BuildingProfileEntity)

    @Query("SELECT * FROM building_profiles WHERE buildingHash = :buildingHash")
    suspend fun getBuildingProfileByHash(buildingHash: String): BuildingProfileEntity?

    @Query("SELECT * FROM building_profiles ORDER BY lastUsedAt DESC")
    fun getAllBuildingProfilesFlow(): Flow<List<BuildingProfileEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMeasurementProfile(profile: MeasurementProfileEntity)

    @Query("SELECT * FROM measurement_profiles WHERE id = :id")
    suspend fun getMeasurementProfileById(id: String): MeasurementProfileEntity?

    @Query("DELETE FROM building_profiles WHERE buildingHash = :buildingHash")
    suspend fun deleteBuildingProfile(buildingHash: String)

    @Query("DELETE FROM measurement_profiles WHERE buildingId = :buildingHash")
    suspend fun deleteMeasurementProfilesForBuilding(buildingHash: String)
}

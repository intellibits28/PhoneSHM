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

    @Query("SELECT * FROM building_profiles WHERE id = :id")
    suspend fun getBuildingProfileById(id: String): BuildingProfileEntity?

    @Query("SELECT * FROM building_profiles")
    fun getAllBuildingProfilesFlow(): Flow<List<BuildingProfileEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMeasurementProfile(profile: MeasurementProfileEntity)

    @Query("SELECT * FROM measurement_profiles WHERE id = :id")
    suspend fun getMeasurementProfileById(id: String): MeasurementProfileEntity?
}

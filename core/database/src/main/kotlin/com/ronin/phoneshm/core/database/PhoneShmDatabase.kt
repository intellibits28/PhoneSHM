package com.ronin.phoneshm.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.ronin.phoneshm.core.database.dao.ProfileDao
import com.ronin.phoneshm.core.database.entity.BuildingProfileEntity
import com.ronin.phoneshm.core.database.entity.MeasurementProfileEntity

/**
 * PhoneShmDatabase: Sovereign local Room DB for metadata, baseline histories, and analysis results.
 * Strictly excludes raw high-frequency sensor sample buffers to prevent database bloat.
 */
@Database(
    entities = [
        BuildingProfileEntity::class,
        MeasurementProfileEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class PhoneShmDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
}

package com.ronin.phoneshm.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.ronin.phoneshm.core.database.dao.BaselineDao
import com.ronin.phoneshm.core.database.dao.ProfileDao
import com.ronin.phoneshm.core.database.entity.BaselineHistoryEntity
import com.ronin.phoneshm.core.database.entity.BaselineProfileEntity
import com.ronin.phoneshm.core.database.entity.BuildingProfileEntity
import com.ronin.phoneshm.core.database.entity.MeasurementProfileEntity

/**
 * PhoneShmDatabase: Sovereign local Room DB for metadata, baseline histories, and analysis results.
 * Strictly excludes raw high-frequency sensor sample buffers to prevent database bloat.
 */
@Database(
    entities = [
        BuildingProfileEntity::class,
        MeasurementProfileEntity::class,
        BaselineProfileEntity::class,
        BaselineHistoryEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class PhoneShmDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun baselineDao(): BaselineDao

    companion object {
        @Volatile
        private var INSTANCE: PhoneShmDatabase? = null

        fun getDatabase(context: Context): PhoneShmDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    PhoneShmDatabase::class.java,
                    "phone_shm_database"
                ).fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

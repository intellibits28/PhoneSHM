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
    version = 7,
    exportSchema = true
)
abstract class PhoneShmDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun baselineDao(): BaselineDao

    companion object {
        @Volatile
        private var INSTANCE: PhoneShmDatabase? = null

        val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_measurement_profiles_buildingId ON measurement_profiles(buildingId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_baseline_history_buildingHash_timestampMs ON baseline_history(buildingHash, timestampMs)")
            }
        }

        fun getDatabase(context: Context): PhoneShmDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    PhoneShmDatabase::class.java,
                    "phone_shm_database"
                ).addMigrations(MIGRATION_6_7)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

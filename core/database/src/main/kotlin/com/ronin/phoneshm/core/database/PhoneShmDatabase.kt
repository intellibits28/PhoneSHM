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
    version = 8,
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

        val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE baseline_profiles ADD COLUMN meanF0XHz REAL")
                db.execSQL("ALTER TABLE baseline_profiles ADD COLUMN stdF0XHz REAL")
                db.execSQL("ALTER TABLE baseline_profiles ADD COLUMN m2F0X REAL")
                db.execSQL("ALTER TABLE baseline_profiles ADD COLUMN meanF0YHz REAL")
                db.execSQL("ALTER TABLE baseline_profiles ADD COLUMN stdF0YHz REAL")
                db.execSQL("ALTER TABLE baseline_profiles ADD COLUMN m2F0Y REAL")
                db.execSQL("ALTER TABLE baseline_profiles ADD COLUMN meanDampingEfdd REAL")
                db.execSQL("ALTER TABLE baseline_profiles ADD COLUMN meanDampingSsi REAL")
                db.execSQL("ALTER TABLE baseline_profiles ADD COLUMN thermalSensitivityHzPerC REAL")
                db.execSQL("ALTER TABLE baseline_profiles ADD COLUMN referenceTemperatureC REAL NOT NULL DEFAULT 25.0")
                db.execSQL("ALTER TABLE baseline_profiles ADD COLUMN meanTempC REAL")
                db.execSQL("ALTER TABLE baseline_profiles ADD COLUMN m2Temp REAL")
                db.execSQL("ALTER TABLE baseline_profiles ADD COLUMN coMomentTempF0 REAL")
                db.execSQL("ALTER TABLE baseline_profiles ADD COLUMN tempSampleCount INTEGER NOT NULL DEFAULT 0")

                db.execSQL("ALTER TABLE baseline_history ADD COLUMN f0XHz REAL")
                db.execSQL("ALTER TABLE baseline_history ADD COLUMN f0YHz REAL")
                db.execSQL("ALTER TABLE baseline_history ADD COLUMN mode2Hz REAL")
                db.execSQL("ALTER TABLE baseline_history ADD COLUMN mode3Hz REAL")
                db.execSQL("ALTER TABLE baseline_history ADD COLUMN dampingEfdd REAL")
                db.execSQL("ALTER TABLE baseline_history ADD COLUMN dampingSsi REAL")
                db.execSQL("ALTER TABLE baseline_history ADD COLUMN consensusVariance REAL")
            }
        }

        fun getDatabase(context: Context): PhoneShmDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    PhoneShmDatabase::class.java,
                    "phone_shm_database"
                ).addMigrations(MIGRATION_6_7, MIGRATION_7_8)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

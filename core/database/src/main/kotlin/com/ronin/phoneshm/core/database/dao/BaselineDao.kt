package com.ronin.phoneshm.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.ronin.phoneshm.core.database.entity.BaselineProfileEntity
import com.ronin.phoneshm.core.database.entity.BaselineHistoryEntity

@Dao
interface BaselineDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(profile: BaselineProfileEntity)

    @Insert
    suspend fun insertHistory(history: BaselineHistoryEntity)

    @Query("SELECT * FROM baseline_profiles WHERE buildingHash = :buildingHash")
    suspend fun getProfile(buildingHash: String): BaselineProfileEntity?

    @Query("SELECT * FROM baseline_history WHERE buildingHash = :buildingHash ORDER BY timestampMs ASC")
    suspend fun getHistory(buildingHash: String): List<BaselineHistoryEntity>

    @Query("DELETE FROM baseline_history WHERE buildingHash = :buildingHash AND id NOT IN (SELECT id FROM baseline_history WHERE buildingHash = :buildingHash ORDER BY timestampMs DESC LIMIT 20)")
    suspend fun trimHistoryTo20(buildingHash: String)

    @Transaction
    suspend fun updateBaselineWithHistory(
        profile: BaselineProfileEntity,
        history: BaselineHistoryEntity
    ) {
        upsertProfile(profile)
        insertHistory(history)
        trimHistoryTo20(profile.buildingHash)
    }

    @Query("DELETE FROM baseline_profiles WHERE buildingHash = :buildingHash")
    suspend fun deleteProfile(buildingHash: String)

    @Query("DELETE FROM baseline_history WHERE buildingHash = :buildingHash")
    suspend fun deleteHistory(buildingHash: String)

    @Query("DELETE FROM baseline_profiles WHERE INSTR(buildingHash, '_') = 0")
    suspend fun deleteOrphanedLegacyProfiles()

    @Query("DELETE FROM baseline_history WHERE INSTR(buildingHash, '_') = 0")
    suspend fun deleteOrphanedLegacyHistory()
}

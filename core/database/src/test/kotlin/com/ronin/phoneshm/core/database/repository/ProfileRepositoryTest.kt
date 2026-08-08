package com.ronin.phoneshm.core.database.repository

import com.ronin.phoneshm.core.database.dao.ProfileDao
import com.ronin.phoneshm.core.database.entity.BuildingProfileEntity
import com.ronin.phoneshm.core.database.entity.MeasurementProfileEntity
import com.ronin.phoneshm.core.database.model.BuildingProfile
import com.ronin.phoneshm.core.database.model.MeasurementProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ProfileRepositoryTest {

    private class FakeProfileDao : ProfileDao {
        private val buildings = mutableMapOf<String, BuildingProfileEntity>()
        private val measurements = mutableMapOf<String, MeasurementProfileEntity>()

        override suspend fun insertBuildingProfile(profile: BuildingProfileEntity) {
            buildings[profile.buildingHash] = profile
        }

        override suspend fun getBuildingProfileByHash(buildingHash: String): BuildingProfileEntity? {
            return buildings[buildingHash]
        }

        override fun getAllBuildingProfilesFlow(): Flow<List<BuildingProfileEntity>> {
            return flowOf(buildings.values.toList())
        }

        override suspend fun insertMeasurementProfile(profile: MeasurementProfileEntity) {
            measurements[profile.id] = profile
        }

        override suspend fun getMeasurementProfileById(id: String): MeasurementProfileEntity? {
            return measurements[id]
        }

        override suspend fun getMeasurementProfilesForBuilding(buildingHash: String): List<MeasurementProfileEntity> {
            return measurements.values.filter { it.buildingId == buildingHash }
        }

        override suspend fun deleteBuildingProfile(buildingHash: String) {
            buildings.remove(buildingHash)
        }

        override suspend fun deleteMeasurementProfilesForBuilding(buildingHash: String) {
            val toRemove = measurements.filterValues { it.buildingId == buildingHash }.keys
            toRemove.forEach { measurements.remove(it) }
        }
    }

    private class FakeBaselineDao : com.ronin.phoneshm.core.database.dao.BaselineDao {
        private val profiles = mutableMapOf<String, com.ronin.phoneshm.core.database.entity.BaselineProfileEntity>()
        private val history = mutableListOf<com.ronin.phoneshm.core.database.entity.BaselineHistoryEntity>()

        override suspend fun upsertProfile(profile: com.ronin.phoneshm.core.database.entity.BaselineProfileEntity) {
            profiles[profile.buildingHash] = profile
        }
        override suspend fun insertHistory(historyEntity: com.ronin.phoneshm.core.database.entity.BaselineHistoryEntity) {
            history.add(historyEntity)
        }
        override suspend fun getProfile(buildingHash: String): com.ronin.phoneshm.core.database.entity.BaselineProfileEntity? = profiles[buildingHash]
        override suspend fun getHistory(buildingHash: String): List<com.ronin.phoneshm.core.database.entity.BaselineHistoryEntity> = history.filter { it.buildingHash == buildingHash }
        override suspend fun trimHistoryTo20(buildingHash: String) {}
        override suspend fun updateBaselineWithHistory(profile: com.ronin.phoneshm.core.database.entity.BaselineProfileEntity, historyEntity: com.ronin.phoneshm.core.database.entity.BaselineHistoryEntity) {}
        override suspend fun deleteProfile(buildingHash: String) { profiles.remove(buildingHash) }
        override suspend fun deleteHistory(buildingHash: String) { history.removeIf { it.buildingHash == buildingHash } }
        override suspend fun deleteOrphanedLegacyProfiles() {}
        override suspend fun deleteOrphanedLegacyHistory() {}
    }

    @Test
    fun testSaveAndGetBuildingProfile() = runTest {
        val dao = FakeProfileDao()
        val baselineDao = FakeBaselineDao()
        val mockContext = io.mockk.mockk<android.content.Context>(relaxed = true)
        val repo = ProfileRepositoryImpl(dao, baselineDao, mockContext)

        val profile = BuildingProfile(
            buildingHash = "hash_abc",
            displayName = "Science Hall",
            buildingType = "RESIDENTIAL_CONCRETE",
            floors = 8,
            material = "Concrete"
        )
        repo.saveBuildingProfile(profile)

        val retrieved = repo.getBuildingProfile("hash_abc")
        assertNotNull(retrieved)
        assertEquals("Science Hall", retrieved?.displayName)
        assertEquals(8, retrieved?.floors)
    }

    @Test
    fun testSaveAndGetMeasurementProfile() = runTest {
        val dao = FakeProfileDao()
        val baselineDao = FakeBaselineDao()
        val mockContext = io.mockk.mockk<android.content.Context>(relaxed = true)
        val repo = ProfileRepositoryImpl(dao, baselineDao, mockContext)

        val profile = MeasurementProfile(
            id = "m_123",
            buildingId = "b_123",
            floorLevel = 4,
            surfaceType = "CERAMIC_TILE",
            locationType = "NEAR_COLUMN",
            placement = "FLAT_ON_FLOOR"
        )
        repo.saveMeasurementProfile(profile)

        val retrieved = repo.getMeasurementProfile("m_123")
        assertNotNull(retrieved)
        assertEquals("CERAMIC_TILE", retrieved?.surfaceType)
        assertEquals("NEAR_COLUMN", retrieved?.locationType)
    }
}

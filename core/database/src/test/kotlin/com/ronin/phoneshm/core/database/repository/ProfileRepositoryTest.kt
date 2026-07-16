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
            buildings[profile.id] = profile
        }

        override suspend fun getBuildingProfileById(id: String): BuildingProfileEntity? {
            return buildings[id]
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
    }

    @Test
    fun testSaveAndGetBuildingProfile() = runTest {
        val dao = FakeProfileDao()
        val repo = ProfileRepositoryImpl(dao)

        val profile = BuildingProfile(
            id = "b_123",
            name = "Science Hall",
            type = "RESIDENTIAL_CONCRETE",
            floors = 8,
            constructionYear = 2015,
            material = "Concrete",
            buildingHash = "hash_abc"
        )
        repo.saveBuildingProfile(profile)

        val retrieved = repo.getBuildingProfile("b_123")
        assertNotNull(retrieved)
        assertEquals("Science Hall", retrieved?.name)
        assertEquals(8, retrieved?.floors)
    }

    @Test
    fun testSaveAndGetMeasurementProfile() = runTest {
        val dao = FakeProfileDao()
        val repo = ProfileRepositoryImpl(dao)

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

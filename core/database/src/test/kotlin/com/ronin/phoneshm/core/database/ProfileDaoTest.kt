package com.ronin.phoneshm.core.database

import com.ronin.phoneshm.core.database.entity.BuildingProfileEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileDaoTest {

    @Test
    fun testBuildingProfileEntityCreation() {
        val entity = BuildingProfileEntity(
            buildingHash = "hash_123",
            displayName = "Test Building",
            buildingType = "RESIDENTIAL_CONCRETE",
            floors = 12,
            constructionYear = 2020,
            material = "Concrete",
            latitude = 37.7749,
            longitude = -122.4194
        )
        assertEquals("hash_123", entity.buildingHash)
        assertEquals(12, entity.floors)
    }
}

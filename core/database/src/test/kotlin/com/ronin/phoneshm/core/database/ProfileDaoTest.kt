package com.ronin.phoneshm.core.database

import com.ronin.phoneshm.core.database.entity.BuildingProfileEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileDaoTest {

    @Test
    fun testBuildingProfileEntityCreation() {
        val entity = BuildingProfileEntity(
            id = "b_1",
            name = "Test Building",
            type = "RESIDENTIAL_CONCRETE",
            floors = 12,
            constructionYear = 2018,
            material = "Concrete",
            buildingHash = "hash_123"
        )
        assertEquals("b_1", entity.id)
        assertEquals(12, entity.floors)
    }
}

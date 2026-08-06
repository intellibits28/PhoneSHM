package com.ronin.phoneshm

import org.junit.Assert.assertEquals
import org.junit.Test

class MainActivityTest {

    @Test
    fun `resolveStartupScreen should resolve to MEASUREMENT if building is valid and exists`() {
        // Arrange & Act
        val result = resolveStartupScreen(
            persistedBuildingId = "someHash",
            buildingExistsInRoom = true,
            initialScreen = "LOADING"
        )
        // Assert
        assertEquals("MEASUREMENT", result)
    }

    @Test
    fun `resolveStartupScreen should resolve to ONBOARDING if building id is null or empty`() {
        assertEquals(
            "ONBOARDING",
            resolveStartupScreen(
                persistedBuildingId = null,
                buildingExistsInRoom = false,
                initialScreen = "LOADING"
            )
        )
        assertEquals(
            "ONBOARDING",
            resolveStartupScreen(
                persistedBuildingId = "",
                buildingExistsInRoom = false,
                initialScreen = "LOADING"
            )
        )
    }

    @Test
    fun `resolveStartupScreen should resolve to ONBOARDING if building exists in preferences but not in Room`() {
        // Edge case: Building was deleted or database was cleared, but SharedPreferences still has the hash.
        val result = resolveStartupScreen(
            persistedBuildingId = "deletedHash",
            buildingExistsInRoom = false,
            initialScreen = "LOADING"
        )
        assertEquals("ONBOARDING", result)
    }
}

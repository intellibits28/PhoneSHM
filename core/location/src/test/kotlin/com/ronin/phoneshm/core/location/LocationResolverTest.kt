package com.ronin.phoneshm.core.location

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class LocationResolverTest {

    private class FakeLocationResolver : LocationResolver {
        override suspend fun resolveLocation(privacyLevel: PrivacyLevel): LocationProfile? {
            return LocationProfile(
                latitude = 16.8409,
                longitude = 96.1735,
                accuracyMeters = 4.5f,
                source = "Network/OSM",
                buildingHash = generateBuildingHash(16.8409, 96.1735, "Yangon_RC_1"),
                privacyLevel = privacyLevel
            )
        }

        override fun generateBuildingHash(lat: Double, lon: Double, buildingName: String): String {
            return "hash_${lat.toInt()}_${lon.toInt()}_$buildingName"
        }
    }

    @Test
    fun testLocationResolutionAndHash() = runTest {
        val resolver = FakeLocationResolver()
        val loc = resolver.resolveLocation(PrivacyLevel.EXACT_LOCATION)
        assertNotNull(loc)
        assertEquals("hash_16_96_Yangon_RC_1", loc?.buildingHash)
    }
}

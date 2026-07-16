package com.ronin.phoneshm.core.location

import android.content.ContextWrapper
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidLocationResolverTest {

    private class TestContext : ContextWrapper(null) {
        override fun getSystemService(name: String): Any? {
            throw UnsupportedOperationException("Unit test fallback")
        }
    }

    @Test
    fun testGenerateBuildingHashClustering() {
        val context = TestContext()
        val resolver = AndroidLocationResolver(context)

        // Coordinates very close to each other should generate the same hash if within the 4-decimal grid (~11m)
        val lat1 = 16.84091
        val lon1 = 96.17351
        val name = "Ahlone Condominium"

        val lat2 = 16.84094
        val lon2 = 96.17354

        val hash1 = resolver.generateBuildingHash(lat1, lon1, name)
        val hash2 = resolver.generateBuildingHash(lat2, lon2, name)

        // Since format is %.4f, both lat1/lat2 round to 16.8409 and lon1/lon2 round to 96.1735
        assertEquals(hash1, hash2)
        assertEquals(16, hash1.length)
    }

    @Test
    fun testPrivacyLevelApproximation() = runTest {
        val context = TestContext()
        val resolver = AndroidLocationResolver(context)

        val approximateProfile = resolver.resolveLocation(PrivacyLevel.APPROXIMATE_LOCATION)
        assertNotNull(approximateProfile)
        assertEquals(16.841, approximateProfile?.latitude ?: 0.0, 0.001)
        assertEquals(96.174, approximateProfile?.longitude ?: 0.0, 0.001)
        assertEquals(110.0f, approximateProfile?.accuracyMeters ?: 0.0f)
        assertTrue(approximateProfile?.source?.endsWith("APPROXIMATE") ?: false)
    }

    @Test
    fun testPrivacyLevelLocalOnly() = runTest {
        val context = TestContext()
        val resolver = AndroidLocationResolver(context)

        val localProfile = resolver.resolveLocation(PrivacyLevel.LOCAL_ONLY)
        assertNotNull(localProfile)
        assertEquals(0.0, localProfile?.latitude ?: 0.0, 0.0)
        assertEquals(0.0, localProfile?.longitude ?: 0.0, 0.0)
        assertEquals("local_only_anonymized", localProfile?.buildingHash)
    }
}

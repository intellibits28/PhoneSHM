package com.ronin.phoneshm.core.baseline

import com.ronin.phoneshm.core.database.dao.BaselineDao
import com.ronin.phoneshm.core.database.entity.BaselineHistoryEntity
import com.ronin.phoneshm.core.database.entity.BaselineProfileEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import kotlin.math.sqrt

class DefaultBaselineManagerEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var baselineDir: File
    private lateinit var engine: DefaultBaselineManagerEngine
    private lateinit var fakeDao: FakeBaselineDao

    class FakeBaselineDao : BaselineDao {
        val profiles = mutableMapOf<String, BaselineProfileEntity>()
        val histories = mutableMapOf<String, MutableList<BaselineHistoryEntity>>()

        override suspend fun upsertProfile(profile: BaselineProfileEntity) {
            profiles[profile.buildingHash] = profile
        }
        override suspend fun insertHistory(history: BaselineHistoryEntity) {
            histories.getOrPut(history.buildingHash) { mutableListOf() }.add(history)
        }
        override suspend fun getProfile(buildingHash: String) = profiles[buildingHash]
        override suspend fun getHistory(buildingHash: String): List<BaselineHistoryEntity> {
            return histories[buildingHash]?.sortedBy { it.timestampMs } ?: emptyList()
        }
        override suspend fun trimHistoryTo20(buildingHash: String) {
            val list = histories[buildingHash] ?: return
            if (list.size > 20) {
                list.sortBy { it.timestampMs }
                histories[buildingHash] = list.takeLast(20).toMutableList()
            }
        }
        override suspend fun deleteProfile(buildingHash: String) {
            profiles.remove(buildingHash)
            histories.remove(buildingHash)
        }
    }

    @Before
    fun setup() {
        baselineDir = Files.createTempDirectory("baseline_test").toFile()
        fakeDao = FakeBaselineDao()
        engine = DefaultBaselineManagerEngine(fakeDao, baselineDir)
    }

    // --- Test 1: No baseline exists yet ---

    @Test
    fun testGetBaselineReturnsNullForNewBuilding() = runTest {
        val result = engine.getOrCreateBaseline("building_abc123")
        assertNull("Should return null for a building with no prior measurements", result)
    }

    // --- Test 2: First update creates baseline ---

    @Test
    fun testFirstUpdateCreatesBaseline() = runTest {
        engine.updateBaselineWithSession("bldg_001", 3.33, 85)

        val profile = engine.getOrCreateBaseline("bldg_001")
        assertNotNull("Baseline should exist after first update", profile)
        assertEquals(3.33, profile!!.meanF0Hz, 1e-9)
        assertEquals(0.0, profile.stdF0Hz, 1e-9)
        assertEquals(1, profile.measurementCount)
        assertEquals(0, profile.consecutiveAnomalyCount)
        assertEquals("bldg_001", profile.buildingHash)
    }

    // --- Test 3: Get baseline after update ---

    @Test
    fun testGetBaselineAfterUpdate() = runTest {
        engine.updateBaselineWithSession("hash_xyz", 5.5, 90)
        val profile = engine.getOrCreateBaseline("hash_xyz")

        assertNotNull(profile)
        assertEquals(5.5, profile!!.meanF0Hz, 1e-9)
        assertEquals(1, profile.measurementCount)
    }

    // --- Test 4: Compare with no baseline ---

    @Test
    fun testCompareWithNoBaseline() = runTest {
        val result = engine.compareWithBaseline("unknown_building", 4.2)

        assertEquals(4.2, result.currentF0Hz, 1e-9)
        assertNull(result.baselineProfile)
        assertEquals(0.0, result.percentageShift, 1e-9)
        assertFalse("No anomaly when no baseline exists", result.isAnomaly)
        assertFalse("No confirmed anomaly when no baseline exists", result.isConfirmedAnomaly)
        assertTrue(result.diagnosticSummary.contains("first measurement"))
    }

    // --- Test 5: Compare with baseline — normal shift ---

    @Test
    fun testCompareWithBaselineNormalShift() = runTest {
        engine.updateBaselineWithSession("bldg_rc", 3.30, 80)
        engine.updateBaselineWithSession("bldg_rc", 3.33, 85)
        engine.updateBaselineWithSession("bldg_rc", 3.35, 82)
        engine.updateBaselineWithSession("bldg_rc", 3.31, 88)
        engine.updateBaselineWithSession("bldg_rc", 3.34, 90)

        val profile = engine.getOrCreateBaseline("bldg_rc")!!
        val result = engine.compareWithBaseline("bldg_rc", profile.meanF0Hz + 0.01)

        assertFalse("Small shift should not be anomalous", result.isAnomaly)
        assertFalse("Small shift should not be confirmed anomaly", result.isConfirmedAnomaly)
        assertTrue(result.diagnosticSummary.contains("normal"))
    }

    // --- Test 6: Compare with baseline — anomalous >5% shift ---

    @Test
    fun testCompareWithBaselineAnomalousLargeShift() = runTest {
        // Use identical values so std=0, no accidental 2σ anomaly during baseline building
        engine.updateBaselineWithSession("bldg_steel", 8.0, 85)
        engine.updateBaselineWithSession("bldg_steel", 8.0, 90)
        engine.updateBaselineWithSession("bldg_steel", 8.0, 82)

        val profile = engine.getOrCreateBaseline("bldg_steel")!!
        assertEquals(0, profile.consecutiveAnomalyCount)

        // 10% shift
        val result = engine.compareWithBaseline("bldg_steel", 7.2)

        assertTrue("Large shift (>5%) should be anomalous", result.isAnomaly)
        // First anomaly — not yet confirmed (consecutiveAnomalyCount is 0, projected = 1)
        assertFalse("Single anomaly should NOT be confirmed", result.isConfirmedAnomaly)
        assertTrue(result.diagnosticSummary.contains("Monitoring"))
    }

    // --- Test 7: C5 — Consecutive anomaly tracking ---

    @Test
    fun testConsecutiveAnomalyConfirmation() = runTest {
        // Build stable baseline with identical values (std=0, no accidental 2σ anomaly)
        engine.updateBaselineWithSession("bldg_c5", 5.0, 85)
        engine.updateBaselineWithSession("bldg_c5", 5.0, 90)
        engine.updateBaselineWithSession("bldg_c5", 5.0, 82)

        val profileBefore = engine.getOrCreateBaseline("bldg_c5")!!
        assertEquals("Baseline should have 0 anomalies", 0, profileBefore.consecutiveAnomalyCount)

        // First anomalous session (>5% shift: 5.0 → 4.7 = -6%)
        engine.updateBaselineWithSession("bldg_c5", 4.7, 85)
        val profile1 = engine.getOrCreateBaseline("bldg_c5")!!
        assertEquals("First anomaly should set count to 1", 1, profile1.consecutiveAnomalyCount)

        // Second anomalous session
        engine.updateBaselineWithSession("bldg_c5", 4.5, 85)
        val profile2 = engine.getOrCreateBaseline("bldg_c5")!!
        assertEquals("Second anomaly should set count to 2", 2, profile2.consecutiveAnomalyCount)

        // Compare should now show confirmed anomaly
        val result = engine.compareWithBaseline("bldg_c5", 4.3)
        assertTrue("Should be anomalous", result.isAnomaly)
        assertTrue("Should be CONFIRMED anomaly after 2 consecutive", result.isConfirmedAnomaly)
        assertTrue(result.diagnosticSummary.contains("CONFIRMED"))
    }

    // --- Test 8: C5 — Hard reset on normal session ---

    @Test
    fun testConsecutiveAnomalyHardReset() = runTest {
        // Build baseline
        engine.updateBaselineWithSession("bldg_reset", 5.0, 85)
        engine.updateBaselineWithSession("bldg_reset", 5.01, 90)

        // Anomalous session
        engine.updateBaselineWithSession("bldg_reset", 4.7, 85) // >5% shift
        val profile1 = engine.getOrCreateBaseline("bldg_reset")!!
        assertTrue("Should have anomaly count > 0", profile1.consecutiveAnomalyCount > 0)

        // Normal session (close to current mean) — should hard-reset
        val currentMean = engine.getOrCreateBaseline("bldg_reset")!!.meanF0Hz
        engine.updateBaselineWithSession("bldg_reset", currentMean, 85)
        val profile2 = engine.getOrCreateBaseline("bldg_reset")!!
        assertEquals("Normal session should hard-reset anomaly count", 0, profile2.consecutiveAnomalyCount)
    }

    // --- Test 9: Multiple updates — Welford accuracy ---

    @Test
    fun testMultipleUpdatesWelfordAccuracy() = runTest {
        val values = listOf(2.0, 4.0, 6.0, 8.0, 10.0)
        values.forEach { v ->
            engine.updateBaselineWithSession("welford_test", v, 90)
        }

        val profile = engine.getOrCreateBaseline("welford_test")!!
        assertEquals("Mean should be 6.0", 6.0, profile.meanF0Hz, 1e-9)
        assertEquals("Std should be sqrt(8)", sqrt(8.0), profile.stdF0Hz, 1e-9)
        assertEquals(5, profile.measurementCount)
    }

    // --- Test 10: Low quality session rejected ---

    @Test
    fun testLowQualitySessionRejected() = runTest {
        engine.updateBaselineWithSession("bldg_lowq", 5.0, 40)
        val result = engine.getOrCreateBaseline("bldg_lowq")
        assertNull("Low quality (40%) session should be rejected", result)
    }

    @Test
    fun testBorderlineQualityAccepted() = runTest {
        engine.updateBaselineWithSession("bldg_border", 5.0, 50)
        val result = engine.getOrCreateBaseline("bldg_border")
        assertNotNull("Quality score of 50 should be accepted", result)
    }

    // --- Test 11: Persistence across instances ---

    @Test
    fun testPersistenceAndReload() = runTest {
        // Since we are using fakeDao, we can't test actual file reloading the same way without 
        // passing a new instance of the same fakeDao.
        engine.updateBaselineWithSession("bldg_persist", 20.0, 100)
        
        val engine2 = DefaultBaselineManagerEngine(fakeDao, baselineDir)
        val profile = engine2.getOrCreateBaseline("bldg_persist")
        assertNotNull(profile)
        assertEquals(20.0, profile!!.meanF0Hz, 1e-9)
    }

    // --- Test 12: Multiple buildings are independent ---

    @Test
    fun testMultipleBuildingsIndependent() = runTest {
        engine.updateBaselineWithSession("bldg_A", 3.0, 85)
        engine.updateBaselineWithSession("bldg_B", 8.0, 90)
        engine.updateBaselineWithSession("bldg_A", 3.1, 82)

        val profileA = engine.getOrCreateBaseline("bldg_A")!!
        val profileB = engine.getOrCreateBaseline("bldg_B")!!

        assertEquals(2, profileA.measurementCount)
        assertEquals(1, profileB.measurementCount)
        assertEquals(3.05, profileA.meanF0Hz, 1e-9)
        assertEquals(8.0, profileB.meanF0Hz, 1e-9)
    }

    // --- Test 13: Comparison reflects updated baseline ---

    @Test
    fun testComparisonReflectsUpdatedBaseline() = runTest {
        engine.updateBaselineWithSession("bldg_evolve", 5.0, 85)

        val result1 = engine.compareWithBaseline("bldg_evolve", 5.0)
        assertEquals(0.0, result1.percentageShift, 1e-9)
        assertFalse(result1.isAnomaly)
        assertFalse(result1.isConfirmedAnomaly)

        engine.updateBaselineWithSession("bldg_evolve", 5.2, 90)

        val result2 = engine.compareWithBaseline("bldg_evolve", 5.0)
        assertTrue(result2.percentageShift < 0)
    }

    // --- Test 14: Reset Baseline ---

    @Test
    fun testResetBaseline() = runTest {
        engine.updateBaselineWithSession("bldg_corrupted", 26.8, 90)
        engine.updateBaselineWithSession("bldg_corrupted", 3.2, 90) // pollute
        
        assertNotNull(engine.getOrCreateBaseline("bldg_corrupted"))
        
        val wasReset = engine.resetBaseline("bldg_corrupted")
        assertTrue(wasReset)
        assertNull(engine.getOrCreateBaseline("bldg_corrupted"))
        
        // Also check persistence
        val engine2 = DefaultBaselineManagerEngine(fakeDao, baselineDir)
        assertNull(engine2.getOrCreateBaseline("bldg_corrupted"))
    }
    
    // --- Test 15: History Ring Buffer ---
    @Test
    fun testHistoryRingBuffer() = runTest {
        for (i in 1..25) {
            engine.updateBaselineWithSession("bldg_history", 10.0 + i, 80)
        }
        val history = engine.getRecentHistory("bldg_history")
        assertEquals(20, history.size)
        // The first 5 should be evicted. The oldest remaining is 10.0 + 6 = 16.0
        assertEquals(16.0, history.first().f0Hz, 1e-9)
        // The newest is 10.0 + 25 = 35.0
        assertEquals(35.0, history.last().f0Hz, 1e-9)
    }

    // --- Test 16: Quality Gate ---

    @Test
    fun testLowQualitySessionsExcludedFromHistory() = runTest {
        engine.updateBaselineWithSession("bldg_x", 10.0, qualityScorePct = 30)
        assertEquals(0, engine.getRecentHistory("bldg_x").size)
    }

    // --- ITEM C: Confirm Incident Baseline Reset ---
    @Test
    fun testResetIncidentBaseline() = runTest {
        val incidentHash = "incident_masonry_1_floor"
        val file = java.io.File(baselineDir, "baseline_profiles.txt")
        file.writeText("# PhoneSHM Baseline Profiles v1.4.1\n")
        file.appendText("$incidentHash|26.801|13.07|9|123456789|1366.5|0|\n")

        val engineLocal = DefaultBaselineManagerEngine(fakeDao, baselineDir)
        val before = engineLocal.getOrCreateBaseline(incidentHash)
        
        println("=== ITEM C OUTPUT ===")
        println("BuildingHash: ${before?.buildingHash}")
        println("Profile BEFORE reset: meanF0Hz=${before?.meanF0Hz}, stdF0Hz=${before?.stdF0Hz}, n=${before?.measurementCount}")
        
        val wasReset = engineLocal.resetBaseline(incidentHash)
        println("Reset action success: $wasReset")
        
        val after = engineLocal.getOrCreateBaseline(incidentHash)
        println("Profile AFTER reset: $after")
        println("=====================")
        
        assertNull(after)
    }

    // --- ITEM E: Room Migration Test ---
    @Test
    fun testMigrationFromOldFormat() = runTest {
        val legacyHash = "legacy_bldg_hash"
        val file = java.io.File(baselineDir, "baseline_profiles.txt")
        file.writeText("# PhoneSHM Baseline Profiles v1.4.1\n")
        // Format: hash|mean|std|n|timestamp|m2|consec_anomaly|history
        // history: timestamp,f0,quality;...
        file.appendText("$legacyHash|25.5|2.1|5|1000000|15.0|1|999990,25.4,80;1000000,25.6,90\n")

        // First creation will trigger ensureMigrated() implicitly on any API call
        val migratorEngine = DefaultBaselineManagerEngine(fakeDao, baselineDir)
        
        // This triggers migration
        val profile = migratorEngine.getOrCreateBaseline(legacyHash)
        
        assertNotNull(profile)
        assertEquals(25.5, profile!!.meanF0Hz, 1e-9)
        assertEquals(2.1, profile.stdF0Hz, 1e-9)
        assertEquals(5, profile.measurementCount)
        
        // Assert history migrated
        val history = migratorEngine.getRecentHistory(legacyHash)
        assertEquals(2, history.size)
        assertEquals(999990L, history[0].timestampMs)
        assertEquals(25.4, history[0].f0Hz, 1e-9)
        assertEquals(1000000L, history[1].timestampMs)
        assertEquals(25.6, history[1].f0Hz, 1e-9)

        // Ensure old file was renamed
        assertFalse(file.exists())
        assertTrue(java.io.File(baselineDir, "baseline_profiles.txt.bak").exists())
    }
}

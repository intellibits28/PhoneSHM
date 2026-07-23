package com.ronin.phoneshm.core.baseline

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.sqrt

class DefaultBaselineManagerEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var baselineDir: java.io.File
    private lateinit var engine: DefaultBaselineManagerEngine

    @Before
    fun setup() {
        baselineDir = tempFolder.newFolder("baseline_test")
        engine = DefaultBaselineManagerEngine(baselineDir)
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
    fun testPersistenceAcrossInstances() = runTest {
        engine.updateBaselineWithSession("persist_bldg", 3.5, 85)
        engine.updateBaselineWithSession("persist_bldg", 3.6, 90)

        val profile1 = engine.getOrCreateBaseline("persist_bldg")!!
        assertEquals(2, profile1.measurementCount)

        // Create a NEW engine instance with the same directory
        val engine2 = DefaultBaselineManagerEngine(baselineDir)
        val profile2 = engine2.getOrCreateBaseline("persist_bldg")

        assertNotNull("Baseline should persist across engine instances", profile2)
        assertEquals(profile1.meanF0Hz, profile2!!.meanF0Hz, 1e-9)
        assertEquals(profile1.stdF0Hz, profile2.stdF0Hz, 1e-9)
        assertEquals(profile1.measurementCount, profile2.measurementCount)
        assertEquals(profile1.consecutiveAnomalyCount, profile2.consecutiveAnomalyCount)
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
}

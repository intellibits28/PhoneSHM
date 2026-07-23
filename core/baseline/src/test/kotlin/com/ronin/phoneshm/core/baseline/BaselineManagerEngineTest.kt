package com.ronin.phoneshm.core.baseline

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BaselineManagerEngineTest {

    private class FakeBaselineManagerEngine : BaselineManagerEngine {
        private val storage = mutableMapOf<String, BaselineProfile>()

        override suspend fun getOrCreateBaseline(buildingHash: String): BaselineProfile? {
            return storage[buildingHash]
        }

        override suspend fun compareWithBaseline(buildingHash: String, currentF0Hz: Double): BaselineComparisonResult {
            val baseline = storage[buildingHash]
            if (baseline == null) {
                return BaselineComparisonResult(currentF0Hz, null, 0.0, false, false, "No prior baseline established.")
            }
            val shift = ((currentF0Hz - baseline.meanF0Hz) / baseline.meanF0Hz) * 100.0
            val isAnomaly = Math.abs(currentF0Hz - baseline.meanF0Hz) > (2.0 * baseline.stdF0Hz)
            return BaselineComparisonResult(currentF0Hz, baseline, shift, isAnomaly, false, "Shift calculated: $shift%")
        }

        override suspend fun updateBaselineWithSession(buildingHash: String, currentF0Hz: Double, qualityScorePct: Int) {
            val existing = storage[buildingHash]
            if (existing == null) {
                storage[buildingHash] = BaselineProfile(buildingHash, currentF0Hz, 0.1, 1, 0, System.currentTimeMillis())
            } else {
                val newCount = existing.measurementCount + 1
                val newMean = (existing.meanF0Hz * existing.measurementCount + currentF0Hz) / newCount
                storage[buildingHash] = existing.copy(meanF0Hz = newMean, measurementCount = newCount)
            }
        }
    }

    @Test
    fun testBaselineComparison() = runTest {
        val engine = FakeBaselineManagerEngine()
        engine.updateBaselineWithSession("hash_123", 8.2, 95)
        val result = engine.compareWithBaseline("hash_123", 8.0)
        assertTrue(result.percentageShift < 0.0)
        assertEquals(8.0, result.currentF0Hz, 0.001)
    }
}

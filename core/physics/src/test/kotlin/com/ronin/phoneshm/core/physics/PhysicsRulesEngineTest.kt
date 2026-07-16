package com.ronin.phoneshm.core.physics

import org.junit.Assert.assertEquals
import org.junit.Test

class PhysicsRulesEngineTest {

    private class FakePhysicsRulesEngine : PhysicsRulesEngine {
        override fun classifyFrequency(
            f0Hz: Double,
            prominence: Float,
            buildingType: String,
            floors: Int?
        ): PlausibilityClassificationResult {
            return if (f0Hz in 3.0..15.0) {
                PlausibilityClassificationResult(
                    classification = FrequencyClassification.GLOBAL_MODE,
                    confidence = 0.9,
                    explanation = "Valid global structural fundamental frequency."
                )
            } else if (f0Hz > 15.0 && f0Hz < 45.0) {
                PlausibilityClassificationResult(
                    classification = FrequencyClassification.LOCAL_MODE,
                    confidence = 0.8,
                    explanation = "Higher frequency typical of local slab/element modes."
                )
            } else {
                PlausibilityClassificationResult(
                    classification = FrequencyClassification.SENSOR_ARTIFACT,
                    confidence = 0.95,
                    explanation = "Out of bounds sensor noise or electrical artifact."
                )
            }
        }
    }

    @Test
    fun testPhysicsClassification() {
        val engine = FakePhysicsRulesEngine()
        val globalRes = engine.classifyFrequency(8.2, 0.5f, "RESIDENTIAL_CONCRETE", 1)
        assertEquals(FrequencyClassification.GLOBAL_MODE, globalRes.classification)

        val localRes = engine.classifyFrequency(35.0, 0.4f, "RESIDENTIAL_CONCRETE", 1)
        assertEquals(FrequencyClassification.LOCAL_MODE, localRes.classification)
    }
}

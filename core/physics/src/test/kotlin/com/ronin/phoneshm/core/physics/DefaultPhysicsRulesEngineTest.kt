package com.ronin.phoneshm.core.physics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultPhysicsRulesEngineTest {

    private val engine = DefaultPhysicsRulesEngine()

    @Test
    fun testLowProminenceOrZeroFrequencyReturnsUnknown() {
        val resZero = engine.classifyFrequency(0.0, 0.5f, "RESIDENTIAL_CONCRETE", 3)
        assertEquals(FrequencyClassification.UNKNOWN, resZero.classification)

        val resLowProminence = engine.classifyFrequency(5.0, 0.02f, "RESIDENTIAL_CONCRETE", 3)
        assertEquals(FrequencyClassification.UNKNOWN, resLowProminence.classification)
    }

    @Test
    fun testLowOrHighFrequenciesClassifiedAsSensorArtifact() {
        val resLow = engine.classifyFrequency(0.15, 0.8f, "RESIDENTIAL_CONCRETE", 3)
        assertEquals(FrequencyClassification.SENSOR_ARTIFACT, resLow.classification)
        assertTrue(resLow.confidence > 0.9)

        val resHigh = engine.classifyFrequency(48.5, 0.8f, "RESIDENTIAL_CONCRETE", 3)
        assertEquals(FrequencyClassification.SENSOR_ARTIFACT, resHigh.classification)
        assertTrue(resHigh.confidence > 0.9)
    }

    @Test
    fun testGlobalModeClassificationForConcreteBuilding() {
        // Concrete 5 floors: expected f0 ~ 10 / 5 = 2.0 Hz. Range ~ [0.9, 5.2 Hz]
        val res = engine.classifyFrequency(2.2, 0.75f, "RESIDENTIAL_CONCRETE", 5)
        assertEquals(FrequencyClassification.GLOBAL_MODE, res.classification)
        assertTrue(res.confidence >= 0.8)
        assertTrue(res.explanation.contains("expected global fundamental resonance"))
    }

    @Test
    fun testGlobalModeClassificationForSteelBuilding() {
        // Steel 4 floors: expected f0 ~ 12 / 4 = 3.0 Hz. Range ~ [1.2, 8.4 Hz]
        val res = engine.classifyFrequency(3.5, 0.8f, "STEEL_FRAME", 4)
        assertEquals(FrequencyClassification.GLOBAL_MODE, res.classification)
    }

    @Test
    fun testLocalModeClassificationForHigherFrequency() {
        // Concrete 5 floors: 28 Hz is above global mode band (5.2 Hz max) but under 45 Hz
        val res = engine.classifyFrequency(28.0, 0.6f, "RESIDENTIAL_CONCRETE", 5)
        assertEquals(FrequencyClassification.LOCAL_MODE, res.classification)
        assertTrue(res.explanation.contains("local slab/element resonance"))
    }
}

package com.ronin.phoneshm.core.modal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RovingModeShapeEngineTest {

    private val engine: RovingModeShapeEngine = DefaultRovingModeShapeEngine()

    @Test
    fun testAmbientVarianceNormalization() {
        // True normalized shear/cantilever mode shape across 4 floors: [0.25, 0.55, 0.80, 1.00]
        val f0 = 2.20
        val kScale = 10.0 // proportionality constant between modal PSD peak and ambient broadband variance

        // Ambient broadband excitation variance varies by up to 4x across sequential floor sessions
        val varFloor1 = 1.0e-4
        val varFloor2SessionA = 2.5e-4
        val varFloor2SessionB = 3.5e-4
        val varFloor3 = 1.8e-4
        val varRoof = 4.0e-4

        val observations = listOf(
            FloorModalObservation(
                floorLevel = 1,
                floorName = "Floor 1",
                f0Hz = f0,
                peakSingularValue = kScale * (0.25 * 0.25) * varFloor1,
                stationaryBroadbandRmsVariance = varFloor1,
                dominantAzimuthDegrees = 15.0,
                qualityScore = 0.90
            ),
            // Two sessions on Floor 2 with different excitation levels and quality weights
            FloorModalObservation(
                floorLevel = 2,
                floorName = "Floor 2 (A)",
                f0Hz = f0 * 1.005,
                peakSingularValue = kScale * (0.55 * 0.55) * varFloor2SessionA,
                stationaryBroadbandRmsVariance = varFloor2SessionA,
                dominantAzimuthDegrees = 16.0,
                qualityScore = 0.80
            ),
            FloorModalObservation(
                floorLevel = 2,
                floorName = "Floor 2 (B)",
                f0Hz = f0 * 0.995,
                peakSingularValue = kScale * (0.55 * 0.55) * varFloor2SessionB,
                stationaryBroadbandRmsVariance = varFloor2SessionB,
                dominantAzimuthDegrees = 14.0,
                qualityScore = 1.00
            ),
            FloorModalObservation(
                floorLevel = 3,
                floorName = "Floor 3",
                f0Hz = f0,
                peakSingularValue = kScale * (0.80 * 0.80) * varFloor3,
                stationaryBroadbandRmsVariance = varFloor3,
                dominantAzimuthDegrees = 15.5,
                qualityScore = 0.95
            ),
            FloorModalObservation(
                floorLevel = 4,
                floorName = "Roof",
                f0Hz = f0,
                peakSingularValue = kScale * (1.00 * 1.00) * varRoof,
                stationaryBroadbandRmsVariance = varRoof,
                dominantAzimuthDegrees = 15.0,
                qualityScore = 1.00
            )
        )

        val result = engine.synthesizeModeShape(observations, frequencyTolerancePct = 5.0)
        assertNotNull("Expected valid mode shape synthesis", result)
        result!!

        assertEquals(listOf(1, 2, 3, 4), result.floorLevels)
        assertEquals(2.20, result.referenceFrequencyHz, 0.02)
        assertEquals(0.25, result.normalizedAmplitudes[0], 1e-6)
        assertEquals(0.55, result.normalizedAmplitudes[1], 1e-6)
        assertEquals(0.80, result.normalizedAmplitudes[2], 1e-6)
        assertEquals(1.00, result.normalizedAmplitudes[3], 1e-6)

        assertEquals(listOf(1.0, 1.0, 1.0, 1.0), result.phaseSigns)
        assertTrue("Expected monotonic first-mode shear profile", result.isMonotonicShearProfile)
    }

    @Test
    fun testOppositeAzimuthPhaseFlipAndOutlierFrequencyRejection() {
        val f0 = 2.40
        val ambVar = 2.0e-4

        val observations = listOf(
            FloorModalObservation(
                floorLevel = 1,
                floorName = "Floor 1",
                f0Hz = f0,
                peakSingularValue = (0.40 * 0.40) * ambVar,
                stationaryBroadbandRmsVariance = ambVar,
                dominantAzimuthDegrees = 20.0
            ),
            // Floor 2 has 180-degree opposite azimuth (-160 deg vs +20 deg -> cos(-180 deg) = -1 < 0)
            FloorModalObservation(
                floorLevel = 2,
                floorName = "Floor 2",
                f0Hz = f0 * 1.01,
                peakSingularValue = (0.70 * 0.70) * ambVar,
                stationaryBroadbandRmsVariance = ambVar,
                dominantAzimuthDegrees = -160.0
            ),
            // Floor 3 has an outlier frequency (4.10 Hz vs 2.40 Hz > 5% tolerance) -> must be rejected
            FloorModalObservation(
                floorLevel = 3,
                floorName = "Floor 3 (Outlier)",
                f0Hz = 4.10,
                peakSingularValue = (0.90 * 0.90) * ambVar,
                stationaryBroadbandRmsVariance = ambVar,
                dominantAzimuthDegrees = 20.0
            ),
            FloorModalObservation(
                floorLevel = 4,
                floorName = "Roof",
                f0Hz = f0,
                peakSingularValue = (1.00 * 1.00) * ambVar,
                stationaryBroadbandRmsVariance = ambVar,
                dominantAzimuthDegrees = 20.0
            )
        )

        val result = engine.synthesizeModeShape(observations, frequencyTolerancePct = 5.0)
        assertNotNull("Expected mode shape synthesis after rejecting outlier floor", result)
        result!!

        // Floor 3 outlier must be excluded
        assertEquals(listOf(1, 2, 4), result.floorLevels)

        // Floor 2 must have phaseSign = -1.0 and negative normalized amplitude -0.70
        assertEquals(1.0, result.phaseSigns[0], 1e-6)
        assertEquals(-1.0, result.phaseSigns[1], 1e-6)
        assertEquals(1.0, result.phaseSigns[2], 1e-6)

        assertEquals(0.40, result.normalizedAmplitudes[0], 1e-6)
        assertEquals(-0.70, result.normalizedAmplitudes[1], 1e-6)
        assertEquals(1.00, result.normalizedAmplitudes[2], 1e-6)

        assertFalse("Profile with phase flip must not be flagged as monotonic first-mode shear", result.isMonotonicShearProfile)
    }
}

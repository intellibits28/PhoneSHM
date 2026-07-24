package com.ronin.phoneshm.core.quality

import com.ronin.phoneshm.core.audio.AudioContextResult
import com.ronin.phoneshm.core.device.DeviceCapabilityReport
import com.ronin.phoneshm.core.device.SensorQualityTier
import com.ronin.phoneshm.core.modal.ModalAnalysisResult
import com.ronin.phoneshm.core.physics.FrequencyClassification
import com.ronin.phoneshm.core.physics.PlausibilityClassificationResult
import com.ronin.phoneshm.core.sensor.MeasurementSessionMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultQualityScoreEngineTest {

    private val engine = DefaultQualityScoreEngine()

    private fun createDummySession(
        jitterMs: Float = 0.05f,
        driftPpm: Float = 120.0f
    ) = MeasurementSessionMetadata(
        sessionId = "sess_1",
        measurementProfileId = "b_1",
        deviceCapabilityReportId = "dev_1",
        targetDurationSeconds = 66,
        targetSampleRateHz = 100,
        actualAverageSampleRateHz = 100.24f,
        sampleJitterStdMs = jitterMs,
        clockDriftPpm = driftPpm,
        rawStorageFileUri = "/tmp/sess_1.bin"
    )

    private fun createDummyDevice(
        qualityTier: SensorQualityTier = SensorQualityTier.RESEARCH_GRADE
    ) = DeviceCapabilityReport(
        deviceModel = "Pixel 6",
        sensorVendor = "InvenSense",
        maxSupportedSampleRateHz = 200,
        estimatedNoiseFloorMg = 0.5f,
        accelerometerBias = floatArrayOf(0f, 0f, 0f),
        qualityTier = qualityTier
    )

    private fun createDummyModal(
        persistence: Double = 0.95,
        confidence: Double = 0.90
    ) = ModalAnalysisResult(
        fundamentalFrequencyHz = 8.17,
        dominantAxis = "X",
        confidence = confidence,
        persistence = persistence,
        adaptiveToleranceHz = 0.194,
        classification = PlausibilityClassificationResult(
            classification = FrequencyClassification.GLOBAL_MODE,
            confidence = 0.9,
            explanation = "Valid"
        ),
        dominantPeaksTable = listOf(Pair(8.17, 12.5))
    )

    @Test
    fun testResearchGradeQualityScore() {
        val session = createDummySession()
        val device = createDummyDevice()
        val modal = createDummyModal()

        val report = engine.calculateQualityScore(session, device, null, modal)

        assertTrue(report.totalScorePct >= 85)
        assertEquals("RESEARCH_GRADE", report.qualityCategory)
        assertEquals(30, report.sensorStabilityScore)
        assertEquals(25, report.noiseLevelScore)
    }

    @Test
    fun testDegradedQualityScore() {
        val session = createDummySession(jitterMs = 4.0f, driftPpm = 8000.0f)
        val device = createDummyDevice(qualityTier = SensorQualityTier.UNSUITABLE)
        val modal = createDummyModal(persistence = 0.1, confidence = 0.2)

        val report = engine.calculateQualityScore(session, device, null, modal)

        assertTrue(report.totalScorePct < 60)
        assertTrue(report.qualityCategory == "FAIR" || report.qualityCategory == "UNRELIABLE")
    }
}

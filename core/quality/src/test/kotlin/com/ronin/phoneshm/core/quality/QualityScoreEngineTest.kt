package com.ronin.phoneshm.core.quality

import com.ronin.phoneshm.core.audio.AudioContextResult
import com.ronin.phoneshm.core.device.DeviceCapabilityReport
import com.ronin.phoneshm.core.device.SensorQualityTier
import com.ronin.phoneshm.core.modal.ModalAnalysisResult
import com.ronin.phoneshm.core.physics.FrequencyClassification
import com.ronin.phoneshm.core.physics.PlausibilityClassificationResult
import com.ronin.phoneshm.core.sensor.MeasurementSessionMetadata
import org.junit.Assert.assertEquals
import org.junit.Test

class QualityScoreEngineTest {

    private class FakeQualityScoreEngine : QualityScoreEngine {
        override fun calculateQualityScore(
            session: MeasurementSessionMetadata,
            device: DeviceCapabilityReport,
            audio: AudioContextResult?,
            modal: ModalAnalysisResult
        ): MeasurementQualityReport {
            val sensorScore = if (session.sampleJitterStdMs < 2.0f && session.clockDriftPpm < 100f) 30 else 15
            val noiseScore = if (audio?.eventLabel == "quiet") 25 else 15
            val couplingScore = 25
            val freqScore = if (modal.persistence >= 0.9) 20 else 10
            val total = sensorScore + noiseScore + couplingScore + freqScore
            return MeasurementQualityReport(
                totalScorePct = total,
                sensorStabilityScore = sensorScore,
                noiseLevelScore = noiseScore,
                couplingQualityScore = couplingScore,
                frequencyStabilityScore = freqScore,
                qualityCategory = if (total >= 90) "RESEARCH_GRADE" else "GOOD",
                diagnosticsExplanation = "Score: $total%"
            )
        }
    }

    @Test
    fun testQualityScoreCalculation() {
        val engine = FakeQualityScoreEngine()
        val meta = MeasurementSessionMetadata(
            "s_1", "p_1", "d_1", 30, 100, 100f, 1.5f, 50f, "/path"
        )
        val dev = DeviceCapabilityReport("Mi", "Bosch", 200, 0.5f, floatArrayOf(0f, 0f, 0f), SensorQualityTier.RESEARCH_GRADE)
        val aud = AudioContextResult("quiet", 0.95, 0.05f, 200f, 0.4f)
        val mod = ModalAnalysisResult(
            8.2, "X", 0.95, 0.95, 0.19,
            PlausibilityClassificationResult(FrequencyClassification.GLOBAL_MODE, 0.9, ""),
            emptyList(),
            6.0,
            com.ronin.phoneshm.core.modal.ExcitationSufficiency.SUFFICIENT
        )

        val report = engine.calculateQualityScore(meta, dev, aud, mod)
        assertEquals(100, report.totalScorePct)
        assertEquals("RESEARCH_GRADE", report.qualityCategory)
    }
}

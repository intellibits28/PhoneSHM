package com.ronin.phoneshm.core.quality

import com.ronin.phoneshm.core.audio.AudioContextResult
import com.ronin.phoneshm.core.device.DeviceCapabilityReport
import com.ronin.phoneshm.core.modal.ModalAnalysisResult
import com.ronin.phoneshm.core.sensor.MeasurementSessionMetadata
import kotlin.math.min
import kotlin.math.max

/**
 * DefaultQualityScoreEngine fuses hardware, sensor, acoustic, and modal stability metrics
 * into a single research-grade confidence rating across 4 weighted factors:
 * 1. Sensor stability (30%) - sample jitter std & clock drift PPM
 * 2. Noise level (25%) - acoustic event classification & RMS noise floor
 * 3. Coupling quality (25%) - sensor quality tier
 * 4. Frequency stability (20%) - peak persistence across windows & modal confidence
 */
class DefaultQualityScoreEngine : QualityScoreEngine {

    override fun calculateQualityScore(
        session: MeasurementSessionMetadata,
        device: DeviceCapabilityReport,
        audio: AudioContextResult?,
        modal: ModalAnalysisResult
    ): MeasurementQualityReport {
        // 1. Sensor Stability Score (Max 30%)
        val jitterScore = when {
            session.sampleJitterStdMs <= 0.1f -> 15
            session.sampleJitterStdMs <= 0.5f -> 12
            session.sampleJitterStdMs <= 1.0f -> 8
            else -> max(0, 15 - ((session.sampleJitterStdMs - 1.0f) * 10).toInt())
        }
        val driftScore = when {
            session.clockDriftPpm <= 300.0f -> 15
            session.clockDriftPpm <= 1000.0f -> 12
            session.clockDriftPpm <= 2500.0f -> 8
            else -> max(0, 15 - ((session.clockDriftPpm - 2500.0f) / 500).toInt())
        }
        val sensorStabilityScore = min(30, max(0, jitterScore + driftScore))

        // 2. Noise Level Score (Max 25%)
        val acousticPenalty = if (audio != null) {
            when (audio.eventLabel) {
                "QUIET_AMBIENT" -> 0
                "BACKGROUND_HUM" -> 2
                "SPEECH_DISTANT" -> 5
                "IMPACT_TRANSIENT" -> 12
                "DOOR_SLAM" -> 15
                else -> 8
            }
        } else {
            0
        }
        val noiseLevelScore = min(25, max(0, 25 - acousticPenalty))

        // 3. Coupling Quality Score (Max 25%)
        val tierScore = when (device.qualityTier.name) {
            "RESEARCH_GRADE" -> 15
            "GOOD" -> 12
            "FAIR" -> 8
            "UNSUITABLE" -> 2
            else -> 10
        }
        val couplingQualityScore = min(25, max(0, tierScore + 10))

        // 4. Frequency Stability Score (Max 20%)
        val persistenceComponent = (modal.persistence * 12.0).toInt()
        val confidenceComponent = (modal.confidence * 8.0).toInt()
        val frequencyStabilityScore = min(20, max(0, persistenceComponent + confidenceComponent))

        // Total score calculation
        val totalScorePct = min(100, max(0, sensorStabilityScore + noiseLevelScore + couplingQualityScore + frequencyStabilityScore))

        val qualityCategory = when {
            totalScorePct >= 85 -> "RESEARCH_GRADE"
            totalScorePct >= 70 -> "GOOD"
            totalScorePct >= 50 -> "FAIR"
            else -> "UNRELIABLE"
        }

        val explanation = "Quality Score $totalScorePct% ($qualityCategory): Sensor=$sensorStabilityScore/30, Noise=$noiseLevelScore/25, Coupling=$couplingQualityScore/25, Modal=$frequencyStabilityScore/20."

        return MeasurementQualityReport(
            totalScorePct = totalScorePct,
            sensorStabilityScore = sensorStabilityScore,
            noiseLevelScore = noiseLevelScore,
            couplingQualityScore = couplingQualityScore,
            frequencyStabilityScore = frequencyStabilityScore,
            qualityCategory = qualityCategory,
            diagnosticsExplanation = explanation
        )
    }
}

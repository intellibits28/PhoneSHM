package com.ronin.phoneshm.core.quality

import com.ronin.phoneshm.core.audio.AudioContextResult
import com.ronin.phoneshm.core.device.DeviceCapabilityReport
import com.ronin.phoneshm.core.modal.ModalAnalysisResult
import com.ronin.phoneshm.core.sensor.MeasurementSessionMetadata

/**
 * MeasurementQualityReport summarizes data integrity across 4 weighted parameters:
 * Sensor stability (30%), Noise level (25%), Coupling quality (25%), and Frequency stability (20%).
 */
data class MeasurementQualityReport(
    val totalScorePct: Int,             // 0-100%
    val sensorStabilityScore: Int,      // Max 30% (jitterStd & clockDriftPpm)
    val noiseLevelScore: Int,           // Max 25% (audio event label & RMS)
    val couplingQualityScore: Int,      // Max 25% (gravity vector variance & high frequency noise)
    val frequencyStabilityScore: Int,   // Max 20% (adaptive peak persistence >= 90%)
    val qualityCategory: String,        // "RESEARCH_GRADE", "GOOD", "FAIR", "UNRELIABLE"
    val diagnosticsExplanation: String
)

/**
 * QualityScoreEngine fuses hardware, sensor, acoustic, and modal stability metrics
 * into a single research-grade confidence rating.
 */
interface QualityScoreEngine {
    /**
     * Calculates deterministic quality score across all 4 factors.
     */
    fun calculateQualityScore(
        session: MeasurementSessionMetadata,
        device: DeviceCapabilityReport,
        audio: AudioContextResult?,
        modal: ModalAnalysisResult
    ): MeasurementQualityReport
}

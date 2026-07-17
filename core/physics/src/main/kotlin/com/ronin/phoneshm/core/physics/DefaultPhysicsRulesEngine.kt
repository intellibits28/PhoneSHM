package com.ronin.phoneshm.core.physics

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Default implementation of [PhysicsRulesEngine].
 *
 * Applies structural engineering empirical rules and frequency bounds to classify
 * detected vibration peaks as global fundamental modes, local slab/element vibrations,
 * or non-structural sensor artifacts without hard-rejecting complex structural behaviors.
 */
class DefaultPhysicsRulesEngine : PhysicsRulesEngine {

    override fun classifyFrequency(
        f0Hz: Double,
        prominence: Float,
        buildingType: String,
        floors: Int?
    ): PlausibilityClassificationResult {
        if (f0Hz <= 0.0 || prominence < 0.05f) {
            return PlausibilityClassificationResult(
                classification = FrequencyClassification.UNKNOWN,
                confidence = 0.4,
                explanation = "Candidate frequency ($f0Hz Hz) prominence ($prominence) is insufficient for reliable structural identification."
            )
        }

        if (f0Hz < 0.3) {
            return PlausibilityClassificationResult(
                classification = FrequencyClassification.SENSOR_ARTIFACT,
                confidence = 0.95,
                explanation = "Frequency under 0.3 Hz corresponds to DC drift, thermal transient, or zero-velocity baseline shift."
            )
        }

        if (f0Hz > 45.0) {
            return PlausibilityClassificationResult(
                classification = FrequencyClassification.SENSOR_ARTIFACT,
                confidence = 0.95,
                explanation = "Frequency above 45.0 Hz exceeds structural resonance limits and indicates electrical/acoustic noise or internal device vibration."
            )
        }

        val effectiveFloors = if (floors != null && floors > 0) floors else 3
        val normalizedType = buildingType.uppercase()

        // Empirical building frequency estimation (f ~ C / N_floors)
        val (expectedF0, minGlobalHz, maxGlobalHz) = when {
            normalizedType.contains("STEEL") -> {
                val fExp = 12.0 / effectiveFloors
                Triple(fExp, max(0.4, fExp * 0.4), min(15.0, max(4.0, fExp * 2.8)))
            }
            normalizedType.contains("MASONRY") || normalizedType.contains("BRICK") || normalizedType.contains("BLOCK") -> {
                val fExp = 18.0 / effectiveFloors
                Triple(fExp, max(1.5, fExp * 0.5), min(25.0, max(8.0, fExp * 2.5)))
            }
            normalizedType.contains("TIMBER") || normalizedType.contains("WOOD") || normalizedType.contains("CLT") -> {
                val fExp = 8.0 / effectiveFloors
                Triple(fExp, max(0.8, fExp * 0.4), min(20.0, max(5.0, fExp * 3.0)))
            }
            else -> {
                // Default concrete / RC frame / composite / unknown (f ~ 10 / N_floors)
                val fExp = 10.0 / effectiveFloors
                Triple(fExp, max(0.5, fExp * 0.45), min(18.0, max(5.0, fExp * 2.6)))
            }
        }

        return if (f0Hz in minGlobalHz..maxGlobalHz) {
            val proximityBonus = max(0.0, 0.15 * (1.0 - abs(f0Hz - expectedF0) / expectedF0))
            val conf = min(0.98, 0.70 + (prominence.toDouble() * 0.25) + proximityBonus)
            PlausibilityClassificationResult(
                classification = FrequencyClassification.GLOBAL_MODE,
                confidence = conf,
                explanation = String.format(
                    "Frequency %.2f Hz falls within the expected global fundamental resonance band (%.1f-%.1f Hz) for %s (%d floors).",
                    f0Hz, minGlobalHz, maxGlobalHz, buildingType, effectiveFloors
                )
            )
        } else {
            // Higher frequencies up to 45 Hz represent local modes (slab vibration, high-stiffness secondary spans)
            val conf = min(0.92, 0.65 + (prominence.toDouble() * 0.25))
            PlausibilityClassificationResult(
                classification = FrequencyClassification.LOCAL_MODE,
                confidence = conf,
                explanation = String.format(
                    "Frequency %.2f Hz exceeds the primary global mode band (%.1f-%.1f Hz) and represents local slab/element resonance.",
                    f0Hz, minGlobalHz, maxGlobalHz
                )
            )
        }
    }
}

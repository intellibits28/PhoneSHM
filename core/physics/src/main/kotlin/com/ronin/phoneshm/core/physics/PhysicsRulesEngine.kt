package com.ronin.phoneshm.core.physics

/**
 * FrequencyClassification categorizes resonant candidate peaks by structural domain rules.
 */
enum class FrequencyClassification {
    GLOBAL_MODE,          // Primary structural resonance (e.g. 3Hz - 15Hz on RC building)
    LOCAL_MODE,           // Local slab/floor/element vibration — HIGHER than expected global band
    BELOW_EXPECTED_RANGE, // Sub-band frequency: orientation drift, DC leakage, gravity artifact
    SENSOR_ARTIFACT,      // Electrical/clock harmonic or zero-velocity spike (>45Hz or <0.3Hz)
    UNKNOWN
}

/**
 * PlausibilityClassificationResult details whether a frequency candidate is structurally plausible
 * as a global vs local mode or sensor artifact without hard rejection.
 */
data class PlausibilityClassificationResult(
    val classification: FrequencyClassification,
    val confidence: Double,
    val explanation: String
)

/**
 * PhysicsRulesEngine evaluates candidate structural frequencies against domain rules
 * based on building typology, floor count, and material stiffness properties.
 */
interface PhysicsRulesEngine {
    /**
     * Classifies candidate fundamental frequency f0 given prominence and building characteristics.
     */
    fun classifyFrequency(
        f0Hz: Double,
        prominence: Float,
        buildingType: String,
        floors: Int?
    ): PlausibilityClassificationResult
}

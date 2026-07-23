package com.ronin.phoneshm.core.physics

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Default implementation of [PhysicsRulesEngine].
 *
 * v1.4.1 changes:
 * - (B4) Uses [PhysicsRulesConfig] loaded from rules_v1.json for frequency band lookup
 *   instead of hardcoded thresholds. Structured coefficients + enum dispatch — no
 *   runtime expression parsing, injection-safe.
 * - (A3) BELOW_EXPECTED_RANGE classification for sub-band frequencies (orientation drift,
 *   gravity artifact) — distinct from LOCAL_MODE (high-frequency slab vibration).
 * - (B3) 0.3Hz SENSOR_ARTIFACT boundary documented as classifier-level safety net
 *   for HPF edge leakage. Practically unreachable for most buildings since HPF cutoff
 *   is at 0.5Hz; meaningful only for >30-floor buildings where minExpectedHz < 0.5Hz.
 */
class DefaultPhysicsRulesEngine(
    private val config: PhysicsRulesConfig = try {
        PhysicsRulesConfig.loadBundledConfig()
    } catch (_: Exception) {
        // Fallback: create minimal default config if bundled JSON not available
        createFallbackConfig()
    }
) : PhysicsRulesEngine {

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

        // B3: 0.3Hz boundary is a classifier-level safety net for HPF edge leakage.
        // HPF cutoff at 0.5Hz attenuates this band; this boundary catches residual
        // leakage or misconfigured cutoffs. Only meaningful for >30-floor buildings
        // where minExpectedHz < 0.5Hz.
        if (f0Hz < config.sensorArtifactLowHz) {
            return PlausibilityClassificationResult(
                classification = FrequencyClassification.SENSOR_ARTIFACT,
                confidence = 0.95,
                explanation = "Frequency under ${config.sensorArtifactLowHz} Hz corresponds to DC drift, thermal transient, or zero-velocity baseline shift."
            )
        }

        if (f0Hz > config.sensorArtifactHighHz) {
            return PlausibilityClassificationResult(
                classification = FrequencyClassification.SENSOR_ARTIFACT,
                confidence = 0.95,
                explanation = "Frequency above ${config.sensorArtifactHighHz} Hz exceeds structural resonance limits and indicates electrical/acoustic noise or internal device vibration."
            )
        }

        val effectiveFloors = if (floors != null && floors > 0) floors else 3
        val bandConfig = config.resolveBand(buildingType)

        // B4: Compute frequency band using structured coefficients (enum dispatch)
        val (minGlobalHz, maxGlobalHz) = bandConfig.computeBand(effectiveFloors)
        val expectedF0 = bandConfig.computeExpectedF0(effectiveFloors)

        return when {
            f0Hz in minGlobalHz..maxGlobalHz -> {
                // GLOBAL_MODE: within expected structural resonance band
                val proximityBonus = max(0.0, 0.15 * (1.0 - abs(f0Hz - expectedF0) / expectedF0))
                val conf = min(0.98, 0.70 + (prominence.toDouble() * 0.25) + proximityBonus)
                PlausibilityClassificationResult(
                    classification = FrequencyClassification.GLOBAL_MODE,
                    confidence = conf,
                    explanation = String.format(
                        "Frequency %.2f Hz falls within the expected global fundamental resonance band (%.1f-%.1f Hz) for %s (%d floors, config v%d).",
                        f0Hz, minGlobalHz, maxGlobalHz, buildingType, effectiveFloors, config.version
                    )
                )
            }
            f0Hz < minGlobalHz -> {
                // A3: BELOW_EXPECTED_RANGE — sub-band frequency (orientation drift, gravity artifact)
                val conf = min(0.90, 0.60 + (prominence.toDouble() * 0.20))
                PlausibilityClassificationResult(
                    classification = FrequencyClassification.BELOW_EXPECTED_RANGE,
                    confidence = conf,
                    explanation = String.format(
                        "Frequency %.2f Hz is below the expected global mode band (%.1f-%.1f Hz). Likely orientation drift, gravity artifact, or sub-structural low-frequency excitation.",
                        f0Hz, minGlobalHz, maxGlobalHz
                    )
                )
            }
            else -> {
                // LOCAL_MODE: above expected global band — genuine high-frequency slab/element vibration
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

    companion object {
        /**
         * Creates a minimal fallback config when bundled JSON is unavailable
         * (e.g., during unit tests without classpath resources).
         */
        internal fun createFallbackConfig(): PhysicsRulesConfig {
            val fallbackJson = """
            {
              "version": 1,
              "source": "Hardcoded fallback",
              "bands": {
                "MIXED_HYBRID": {
                  "formula": "K_OVER_FLOORS",
                  "kExpected": 10.0, "bandWidthLow": 0.45, "bandWidthHigh": 2.6,
                  "clampMinHz": 0.5, "clampMaxHz": 18.0,
                  "fallbackMinHz": 0.8, "fallbackMaxHz": 25.0,
                  "aliases": ["CONCRETE", "RC", "STEEL", "MASONRY", "TIMBER", "UNKNOWN"]
                }
              },
              "sensorArtifactBoundary": { "lowHz": 0.3, "highHz": 45.0 }
            }
            """.trimIndent()
            return PhysicsRulesConfig.parseJson(fallbackJson)
        }
    }
}

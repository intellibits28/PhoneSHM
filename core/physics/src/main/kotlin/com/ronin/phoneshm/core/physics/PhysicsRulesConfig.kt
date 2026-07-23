package com.ronin.phoneshm.core.physics

import java.io.InputStream
import kotlin.math.max
import kotlin.math.min

/**
 * PhysicsRulesConfig loads and provides building frequency band configurations
 * from a versioned JSON config file (rules_v1.json).
 *
 * Design decisions (v1.4.1 B4-fix):
 * - No runtime expression parsing — structured coefficients + enum dispatch
 * - Config is immutable after load — thread-safe without synchronization
 * - Remote-config updates only change numeric coefficients, not logic
 *
 * Formula: K_OVER_FLOORS
 *   f_expected = kExpected / floors
 *   minGlobalHz = max(clampMinHz, f_expected * bandWidthLow)
 *   maxGlobalHz = min(clampMaxHz, max(clampMinHz * 2, f_expected * bandWidthHigh))
 */
class PhysicsRulesConfig private constructor(
    val version: Int,
    val source: String,
    val bands: Map<String, BandConfig>,
    val sensorArtifactLowHz: Double,
    val sensorArtifactHighHz: Double,
    private val aliasIndex: Map<String, String>
) {

    enum class FormulaType {
        /** f = kExpected / floors. Standard Rayleigh/code-based approximation. */
        K_OVER_FLOORS,
        /** Fixed frequency band regardless of floor count. */
        FIXED_BAND
    }

    data class BandConfig(
        val formula: FormulaType,
        val kExpected: Double,
        val bandWidthLow: Double,
        val bandWidthHigh: Double,
        val clampMinHz: Double,
        val clampMaxHz: Double,
        val fallbackMinHz: Double,
        val fallbackMaxHz: Double,
        val aliases: List<String>
    ) {
        /**
         * Computes expected f0 for the given floor count.
         */
        fun computeExpectedF0(floors: Int): Double {
            return when (formula) {
                FormulaType.K_OVER_FLOORS -> {
                    if (floors <= 0) return (fallbackMinHz + fallbackMaxHz) / 2.0
                    kExpected / floors
                }
                FormulaType.FIXED_BAND -> (fallbackMinHz + fallbackMaxHz) / 2.0
            }
        }

        /**
         * Computes frequency band [minHz, maxHz] for the given floor count.
         * Matches the original DefaultPhysicsRulesEngine formula:
         *   minHz = max(clampMinHz, fExp * bandWidthLow)
         *   maxHz = min(clampMaxHz, max(clampMinHz * 2, fExp * bandWidthHigh))
         */
        fun computeBand(floors: Int): Pair<Double, Double> {
            return when (formula) {
                FormulaType.K_OVER_FLOORS -> {
                    if (floors <= 0) return Pair(fallbackMinHz, fallbackMaxHz)
                    val fExp = kExpected / floors
                    val minHz = max(clampMinHz, fExp * bandWidthLow)
                    val maxHz = min(clampMaxHz, max(clampMinHz * 2, fExp * bandWidthHigh))
                    Pair(minHz, maxHz)
                }
                FormulaType.FIXED_BAND -> Pair(fallbackMinHz, fallbackMaxHz)
            }
        }
    }

    /**
     * Resolves a building type string to its canonical band config.
     */
    fun resolveBand(buildingType: String): BandConfig {
        val normalized = buildingType.uppercase().trim()

        // 1. Exact match on canonical key
        bands[normalized]?.let { return it }

        // 2. Alias index lookup
        aliasIndex[normalized]?.let { key -> bands[key]?.let { return it } }

        // 3. Substring match on aliases
        for ((_, band) in bands) {
            for (alias in band.aliases) {
                if (normalized.contains(alias) || alias.contains(normalized)) {
                    return band
                }
            }
        }

        // 4. Fallback
        return bands["MIXED_HYBRID"] ?: bands.values.first()
    }

    companion object {
        fun loadFromStream(inputStream: InputStream): PhysicsRulesConfig {
            val jsonText = inputStream.bufferedReader().readText()
            return parseJson(jsonText)
        }

        fun loadBundledConfig(): PhysicsRulesConfig {
            val stream = PhysicsRulesConfig::class.java.classLoader
                ?.getResourceAsStream("rules_v1.json")
                ?: throw IllegalStateException("Bundled rules_v1.json not found in classpath")
            return loadFromStream(stream)
        }

        internal fun parseJson(json: String): PhysicsRulesConfig {
            val version = extractInt(json, "\"version\"") ?: 1
            val source = extractString(json, "\"source\"") ?: ""

            val artifactBlock = extractBlock(json, "\"sensorArtifactBoundary\"")
            val artifactLowHz = extractDouble(artifactBlock, "\"lowHz\"") ?: 0.3
            val artifactHighHz = extractDouble(artifactBlock, "\"highHz\"") ?: 45.0

            val bandsBlock = extractBlock(json, "\"bands\"")
            val bands = mutableMapOf<String, BandConfig>()
            val aliasIndex = mutableMapOf<String, String>()

            val bandKeys = extractTopLevelKeys(bandsBlock)
            for (key in bandKeys) {
                val bandBlock = extractBlock(bandsBlock, "\"$key\"")
                if (bandBlock.isEmpty()) continue

                val formulaStr = extractString(bandBlock, "\"formula\"") ?: "K_OVER_FLOORS"
                val formula = try {
                    FormulaType.valueOf(formulaStr)
                } catch (_: Exception) {
                    FormulaType.K_OVER_FLOORS
                }

                val config = BandConfig(
                    formula = formula,
                    kExpected = extractDouble(bandBlock, "\"kExpected\"") ?: 10.0,
                    bandWidthLow = extractDouble(bandBlock, "\"bandWidthLow\"") ?: 0.45,
                    bandWidthHigh = extractDouble(bandBlock, "\"bandWidthHigh\"") ?: 2.6,
                    clampMinHz = extractDouble(bandBlock, "\"clampMinHz\"") ?: 0.5,
                    clampMaxHz = extractDouble(bandBlock, "\"clampMaxHz\"") ?: 18.0,
                    fallbackMinHz = extractDouble(bandBlock, "\"fallbackMinHz\"") ?: 0.8,
                    fallbackMaxHz = extractDouble(bandBlock, "\"fallbackMaxHz\"") ?: 25.0,
                    aliases = extractStringArray(bandBlock, "\"aliases\"")
                )

                bands[key] = config
                for (alias in config.aliases) {
                    aliasIndex[alias.uppercase()] = key
                }
            }

            return PhysicsRulesConfig(
                version = version,
                source = source,
                bands = bands,
                sensorArtifactLowHz = artifactLowHz,
                sensorArtifactHighHz = artifactHighHz,
                aliasIndex = aliasIndex
            )
        }

        // --- Simple JSON extraction helpers ---

        private fun extractString(json: String, key: String): String? {
            val keyIdx = json.indexOf(key)
            if (keyIdx < 0) return null
            val colonIdx = json.indexOf(':', keyIdx + key.length)
            if (colonIdx < 0) return null
            val startQuote = json.indexOf('"', colonIdx + 1)
            if (startQuote < 0) return null
            val endQuote = json.indexOf('"', startQuote + 1)
            if (endQuote < 0) return null
            return json.substring(startQuote + 1, endQuote)
        }

        private fun extractDouble(json: String, key: String): Double? {
            val keyIdx = json.indexOf(key)
            if (keyIdx < 0) return null
            val colonIdx = json.indexOf(':', keyIdx + key.length)
            if (colonIdx < 0) return null
            val rest = json.substring(colonIdx + 1).trimStart()
            val numStr = rest.takeWhile { it.isDigit() || it == '.' || it == '-' || it == 'e' || it == 'E' }
            return numStr.toDoubleOrNull()
        }

        private fun extractInt(json: String, key: String): Int? {
            return extractDouble(json, key)?.toInt()
        }

        private fun extractBlock(json: String, key: String): String {
            val keyIdx = json.indexOf(key)
            if (keyIdx < 0) return ""
            val braceStart = json.indexOf('{', keyIdx + key.length)
            if (braceStart < 0) return ""
            var depth = 0
            for (i in braceStart until json.length) {
                when (json[i]) {
                    '{' -> depth++
                    '}' -> { depth--; if (depth == 0) return json.substring(braceStart, i + 1) }
                }
            }
            return ""
        }

        private fun extractTopLevelKeys(json: String): List<String> {
            val keys = mutableListOf<String>()
            var depth = 0
            var i = 0
            while (i < json.length) {
                when (json[i]) {
                    '{' -> depth++
                    '}' -> depth--
                    '"' -> {
                        if (depth == 1) {
                            val endQuote = json.indexOf('"', i + 1)
                            if (endQuote > i) {
                                val candidate = json.substring(i + 1, endQuote)
                                val afterKey = json.substring(endQuote + 1).trimStart()
                                if (afterKey.startsWith(":") && afterKey.trimStart().drop(1).trimStart().startsWith("{")) {
                                    keys.add(candidate)
                                }
                                i = endQuote
                            }
                        }
                    }
                }
                i++
            }
            return keys
        }

        private fun extractStringArray(json: String, key: String): List<String> {
            val keyIdx = json.indexOf(key)
            if (keyIdx < 0) return emptyList()
            val bracketStart = json.indexOf('[', keyIdx + key.length)
            if (bracketStart < 0) return emptyList()
            val bracketEnd = json.indexOf(']', bracketStart)
            if (bracketEnd < 0) return emptyList()
            val content = json.substring(bracketStart + 1, bracketEnd)
            return content.split(',')
                .map { it.trim().removeSurrounding("\"") }
                .filter { it.isNotEmpty() }
        }
    }
}

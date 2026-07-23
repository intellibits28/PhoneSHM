package com.ronin.phoneshm.core.baseline

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * DefaultBaselineManagerEngine implements longitudinal structural health tracking
 * using Welford's online algorithm for incremental mean and variance computation.
 *
 * v1.4.1 changes:
 * - (B5) Per-buildingHash Mutex for Welford atomicity. Welford's algorithm is
 *   single-writer only — concurrent updates to the same hash corrupt mean/std.
 * - (B5) Persist-atomicity: Welford update + file persist occur within the same
 *   lock scope using temp file → atomic rename pattern.
 * - (C5) consecutiveAnomalyCount tracks sequential anomalous sessions. User-facing
 *   alert (isConfirmedAnomaly) requires N≥2 consecutive anomalies. Hard-reset on
 *   any normal session.
 *
 * Quality gating: only sessions with qualityScorePct >= 50 update the baseline.
 */
class DefaultBaselineManagerEngine(
    private val baselineDir: File
) : BaselineManagerEngine {

    /**
     * Internal storage record extending BaselineProfile with Welford's M2 accumulator.
     * M2 = sum of squared differences from the running mean, used to compute variance.
     */
    private data class BaselineRecord(
        val profile: BaselineProfile,
        val m2: Double
    )

    private val cache = ConcurrentHashMap<String, BaselineRecord>()
    private val fileMutex = Mutex()
    private val hashMutexes = ConcurrentHashMap<String, Mutex>() // B5: per-buildingHash lock
    private var loaded = false

    private val storageFile: File
        get() = File(baselineDir, "baseline_profiles.txt")

    override suspend fun getOrCreateBaseline(buildingHash: String): BaselineProfile? {
        ensureLoaded()
        return cache[buildingHash]?.profile
    }

    override suspend fun compareWithBaseline(
        buildingHash: String,
        currentF0Hz: Double
    ): BaselineComparisonResult {
        ensureLoaded()
        val record = cache[buildingHash]

        if (record == null) {
            return BaselineComparisonResult(
                currentF0Hz = currentF0Hz,
                baselineProfile = null,
                percentageShift = 0.0,
                isAnomaly = false,
                isConfirmedAnomaly = false,
                diagnosticSummary = "No baseline established. This is the first measurement for this structure."
            )
        }

        val baseline = record.profile
        val percentageShift = ((currentF0Hz - baseline.meanF0Hz) / baseline.meanF0Hz) * 100.0

        // Anomaly detection: >5% absolute shift OR >2σ deviation (when σ > 0)
        val isTwoSigmaAnomaly = baseline.stdF0Hz > 0.0 &&
                abs(currentF0Hz - baseline.meanF0Hz) > 2.0 * baseline.stdF0Hz
        val isLargeShift = abs(percentageShift) > 5.0
        val isAnomaly = isLargeShift || isTwoSigmaAnomaly

        // C5: confirmed anomaly requires N≥2 consecutive anomalous sessions
        // The count will be incremented in updateBaselineWithSession() after this comparison.
        // For now, check if current anomaly + existing streak would reach threshold.
        val projectedCount = if (isAnomaly) baseline.consecutiveAnomalyCount + 1 else 0
        val isConfirmedAnomaly = projectedCount >= 2

        val diagnosticSummary = buildString {
            append(String.format("Current f₀ = %.3f Hz vs baseline μ = %.3f Hz (σ = %.4f Hz, n = %d). ",
                currentF0Hz, baseline.meanF0Hz, baseline.stdF0Hz, baseline.measurementCount))
            append(String.format("Shift: %+.2f%%. ", percentageShift))
            if (isAnomaly) {
                if (isLargeShift) {
                    append("⚠ ANOMALY: Shift exceeds ±5% safety threshold. ")
                }
                if (isTwoSigmaAnomaly) {
                    append(String.format("⚠ ANOMALY: Deviation exceeds 2σ (%.4f Hz > %.4f Hz). ",
                        abs(currentF0Hz - baseline.meanF0Hz), 2.0 * baseline.stdF0Hz))
                }
                if (isConfirmedAnomaly) {
                    append("⚠ CONFIRMED: ${projectedCount} consecutive anomalous sessions. Investigate potential structural degradation.")
                } else {
                    append("Monitoring — confirm with additional measurement (${projectedCount}/2 consecutive anomalies).")
                }
            } else {
                append("Within normal operational range.")
            }
        }

        return BaselineComparisonResult(
            currentF0Hz = currentF0Hz,
            baselineProfile = baseline,
            percentageShift = percentageShift,
            isAnomaly = isAnomaly,
            isConfirmedAnomaly = isConfirmedAnomaly,
            diagnosticSummary = diagnosticSummary
        )
    }

    override suspend fun updateBaselineWithSession(
        buildingHash: String,
        currentF0Hz: Double,
        qualityScorePct: Int
    ) {
        // Quality gate: reject low-quality measurements
        if (qualityScorePct < 50) return

        ensureLoaded()

        // B5: Per-buildingHash mutex for Welford atomicity
        val hashMutex = hashMutexes.computeIfAbsent(buildingHash) { Mutex() }

        // B5: Atomic transaction — Welford update + persist within the SAME lock scope
        hashMutex.withLock {
            val existing = cache[buildingHash]
            val now = System.currentTimeMillis()

            // Determine if this session is anomalous (for consecutive count tracking)
            val isAnomaly = if (existing != null) {
                val baseline = existing.profile
                val pctShift = ((currentF0Hz - baseline.meanF0Hz) / baseline.meanF0Hz) * 100.0
                val isTwoSigma = baseline.stdF0Hz > 0.0 &&
                        abs(currentF0Hz - baseline.meanF0Hz) > 2.0 * baseline.stdF0Hz
                abs(pctShift) > 5.0 || isTwoSigma
            } else false

            val newRecord = if (existing == null) {
                // First measurement: initialize baseline
                BaselineRecord(
                    profile = BaselineProfile(
                        buildingHash = buildingHash,
                        meanF0Hz = currentF0Hz,
                        stdF0Hz = 0.0,
                        measurementCount = 1,
                        consecutiveAnomalyCount = 0, // First measurement is never anomalous
                        lastUpdatedAt = now
                    ),
                    m2 = 0.0
                )
            } else {
                // Welford's online algorithm for incremental mean and variance
                val oldMean = existing.profile.meanF0Hz
                val oldM2 = existing.m2
                val nNew = existing.profile.measurementCount + 1

                val delta = currentF0Hz - oldMean
                val newMean = oldMean + delta / nNew
                val delta2 = currentF0Hz - newMean
                val newM2 = oldM2 + delta * delta2

                // Population standard deviation: sqrt(M2 / n)
                val newStd = if (nNew > 1) sqrt(newM2 / nNew) else 0.0

                // C5: Update consecutive anomaly count
                // Hard reset on normal session; increment on anomaly
                val newAnomalyCount = if (isAnomaly) {
                    existing.profile.consecutiveAnomalyCount + 1
                } else {
                    0 // Hard reset
                }

                BaselineRecord(
                    profile = BaselineProfile(
                        buildingHash = buildingHash,
                        meanF0Hz = newMean,
                        stdF0Hz = newStd,
                        measurementCount = nNew,
                        consecutiveAnomalyCount = newAnomalyCount,
                        lastUpdatedAt = now
                    ),
                    m2 = newM2
                )
            }

            cache[buildingHash] = newRecord

            // B5: Persist WITHIN the hash lock — crash-consistent
            // Uses temp file → atomic rename (POSIX guarantees atomicity)
            persistProfilesAtomic()
        }
    }

    // --- Persistence ---

    private suspend fun ensureLoaded() {
        if (loaded) return
        fileMutex.withLock {
            if (loaded) return
            loadProfiles()
            loaded = true
        }
    }

    private fun loadProfiles() {
        val file = storageFile
        if (!file.exists()) return

        try {
            file.readLines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach

                // Format: buildingHash|meanF0Hz|stdF0Hz|measurementCount|lastUpdatedAt|m2|consecutiveAnomalyCount
                val parts = trimmed.split("|")
                if (parts.size >= 6) {
                    val hash = parts[0]
                    val mean = parts[1].toDoubleOrNull() ?: return@forEach
                    val std = parts[2].toDoubleOrNull() ?: return@forEach
                    val count = parts[3].toIntOrNull() ?: return@forEach
                    val updatedAt = parts[4].toLongOrNull() ?: return@forEach
                    val m2 = parts[5].toDoubleOrNull() ?: return@forEach
                    // C5: backwards-compatible — default to 0 if field not present
                    val anomalyCount = if (parts.size >= 7) parts[6].toIntOrNull() ?: 0 else 0

                    cache[hash] = BaselineRecord(
                        profile = BaselineProfile(
                            buildingHash = hash,
                            meanF0Hz = mean,
                            stdF0Hz = std,
                            measurementCount = count,
                            consecutiveAnomalyCount = anomalyCount,
                            lastUpdatedAt = updatedAt
                        ),
                        m2 = m2
                    )
                }
            }
        } catch (_: Exception) {
            // Corrupted file — start fresh
        }
    }

    private fun serializeProfiles(): String {
        val sb = StringBuilder()
        sb.appendLine("# PhoneSHM Baseline Profiles v1.4.1 (do not edit manually)")
        cache.forEach { (_, record) ->
            val p = record.profile
            // Format: buildingHash|meanF0Hz|stdF0Hz|measurementCount|lastUpdatedAt|m2|consecutiveAnomalyCount
            sb.appendLine("${p.buildingHash}|${p.meanF0Hz}|${p.stdF0Hz}|${p.measurementCount}|${p.lastUpdatedAt}|${record.m2}|${p.consecutiveAnomalyCount}")
        }
        return sb.toString()
    }

    /**
     * B5: Atomic file persistence using temp file + rename.
     * Must be called from within a hash mutex lock to ensure crash-consistency.
     * fileMutex serializes concurrent writes from different buildingHash updates.
     * Lock ordering: hashMutex → fileMutex (never reversed — deadlock-free).
     */
    private suspend fun persistProfilesAtomic() {
        fileMutex.withLock {
            baselineDir.mkdirs()
            val tmpFile = File(baselineDir, "baseline_profiles.tmp")
            val targetFile = storageFile
            try {
                tmpFile.writeText(serializeProfiles())
                // Atomic rename (POSIX guarantees atomicity for same-filesystem rename)
                if (!tmpFile.renameTo(targetFile)) {
                    // Fallback: on some platforms renameTo fails if target exists
                    targetFile.delete()
                    tmpFile.renameTo(targetFile)
                }
            } catch (e: Exception) {
                // Clean up temp file on failure
                tmpFile.delete()
                throw e
            }
        }
    }
}

package com.ronin.phoneshm.core.baseline

import com.ronin.phoneshm.core.database.dao.BaselineDao
import com.ronin.phoneshm.core.database.entity.BaselineHistoryEntity
import com.ronin.phoneshm.core.database.entity.BaselineProfileEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * DefaultBaselineManagerEngine implements longitudinal structural health tracking
 * using Welford's online algorithm for incremental mean and variance computation.
 *
 * Migrated to Room DB per core principles.
 */
class DefaultBaselineManagerEngine(
    private val baselineDao: BaselineDao,
    private val baselineDir: File // Kept only for migration
) : BaselineManagerEngine {

    private var migrationRun = false
    private val migrationMutex = Mutex()

    private val storageFile: File
        get() = File(baselineDir, "baseline_profiles.txt")
        
    private val backupFile: File
        get() = File(baselineDir, "baseline_profiles.txt.bak")

    private suspend fun ensureMigrated() {
        if (migrationRun) return
        migrationMutex.withLock {
            if (migrationRun) return
            if (storageFile.exists()) {
                migrateFromFileToRoom()
            }
            migrationRun = true
        }
    }

    private suspend fun migrateFromFileToRoom() {
        try {
            val file = storageFile
            file.readLines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach

                val parts = trimmed.split("|")
                if (parts.size >= 6) {
                    val hash = parts[0]
                    val mean = parts[1].toDoubleOrNull() ?: return@forEach
                    val std = parts[2].toDoubleOrNull() ?: return@forEach
                    val count = parts[3].toIntOrNull() ?: return@forEach
                    val updatedAt = parts[4].toLongOrNull() ?: return@forEach
                    val m2 = parts[5].toDoubleOrNull() ?: return@forEach
                    val anomalyCount = if (parts.size >= 7) parts[6].toIntOrNull() ?: 0 else 0
                    
                    val existing = baselineDao.getProfile(hash)
                    if (existing == null) {
                        baselineDao.upsertProfile(
                            BaselineProfileEntity(
                                buildingHash = hash,
                                meanF0Hz = mean,
                                stdF0Hz = std,
                                m2 = m2,
                                measurementCount = count,
                                consecutiveAnomalyCount = anomalyCount,
                                lastUpdatedAt = updatedAt
                            )
                        )
                    }

                    val historyStr = if (parts.size >= 8) parts[7] else ""
                    if (historyStr.isNotEmpty()) {
                        historyStr.split(";").forEach { h ->
                            val hParts = h.split(",")
                            if (hParts.size == 3) {
                                baselineDao.insertHistory(
                                    BaselineHistoryEntity(
                                        buildingHash = hash,
                                        timestampMs = hParts[0].toLong(),
                                        f0Hz = hParts[1].toDouble(),
                                        qualityScorePct = hParts[2].toInt()
                                    )
                                )
                            }
                        }
                        baselineDao.trimHistoryTo20(hash)
                    }
                }
            }
            // Rename to backup
            storageFile.renameTo(backupFile)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override suspend fun getOrCreateBaseline(buildingHash: String): BaselineProfile? {
        ensureMigrated()
        val entity = baselineDao.getProfile(buildingHash) ?: return null
        val historyEntities = baselineDao.getHistory(buildingHash)
        
        return BaselineProfile(
            buildingHash = entity.buildingHash,
            meanF0Hz = entity.meanF0Hz,
            stdF0Hz = entity.stdF0Hz,
            measurementCount = entity.measurementCount,
            consecutiveAnomalyCount = entity.consecutiveAnomalyCount,
            lastUpdatedAt = entity.lastUpdatedAt,
            recentHistory = historyEntities.map { BaselineHistoryEntry(it.timestampMs, it.f0Hz, it.qualityScorePct) }
        )
    }

    override suspend fun resetBaseline(buildingHash: String): Boolean {
        ensureMigrated()
        val profile = baselineDao.getProfile(buildingHash)
        if (profile != null) {
            baselineDao.deleteProfile(buildingHash)
            return true
        }
        return false
    }

    override suspend fun getRecentHistory(buildingHash: String): List<BaselineHistoryEntry> {
        ensureMigrated()
        return baselineDao.getHistory(buildingHash).map { 
            BaselineHistoryEntry(it.timestampMs, it.f0Hz, it.qualityScorePct) 
        }
    }

    companion object {
        private const val MIN_BASELINE_SAMPLES = 10
    }

    override suspend fun compareWithBaseline(
        buildingHash: String,
        currentF0Hz: Double,
        confidence: Double
    ): BaselineComparisonResult {
        ensureMigrated()
        val baseline = getOrCreateBaseline(buildingHash)

        if (baseline == null) {
            return BaselineComparisonResult(
                currentF0Hz = currentF0Hz,
                baselineProfile = null,
                percentageShift = 0.0,
                isAnomaly = false,
                isConfirmedAnomaly = false,
                diagnosticSummary = "No baseline established. This is the first measurement for this structure."
            )
        }

        val percentageShift = ((currentF0Hz - baseline.meanF0Hz) / baseline.meanF0Hz) * 100.0

        if (confidence < 0.50) {
            return BaselineComparisonResult(
                currentF0Hz = currentF0Hz,
                baselineProfile = baseline,
                percentageShift = percentageShift,
                isAnomaly = false,
                isConfirmedAnomaly = false,
                diagnosticSummary = "⚠️ Measurement quality too low for reliable comparison — retry recommended",
                comparisonSkippedLowQuality = true,
                isCalibrating = baseline.measurementCount < MIN_BASELINE_SAMPLES
            )
        }

        val isTwoSigmaAnomaly = baseline.stdF0Hz > 0.0 &&
                abs(currentF0Hz - baseline.meanF0Hz) > 2.0 * baseline.stdF0Hz
        val isLargeShift = abs(percentageShift) > 5.0
        val isAnomaly = isLargeShift || isTwoSigmaAnomaly

        val isCalibrating = baseline.measurementCount < MIN_BASELINE_SAMPLES
        val projectedCount = if (isAnomaly) baseline.consecutiveAnomalyCount + 1 else 0
        // E2: Do not confirm anomalies when baseline is calibrating (n < 10)
        val isConfirmedAnomaly = !isCalibrating && projectedCount >= 2

        val diagnosticSummary = buildString {
            if (isCalibrating) {
                append("CALIBRATING BASELINE (${baseline.measurementCount}/$MIN_BASELINE_SAMPLES). ")
            }
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
                } else if (isCalibrating) {
                    append("Calibrating — anomaly detected but baseline establishment in progress.")
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
            diagnosticSummary = diagnosticSummary,
            comparisonSkippedLowQuality = false,
            isCalibrating = isCalibrating
        )
    }

    override suspend fun updateBaselineWithSession(
        buildingHash: String,
        currentF0Hz: Double,
        qualityScorePct: Int
    ) {
        if (qualityScorePct < 50) return
        ensureMigrated()

        val existing = baselineDao.getProfile(buildingHash)
        val now = System.currentTimeMillis()

        if (existing == null) {
            baselineDao.updateBaselineWithHistory(
                BaselineProfileEntity(
                    buildingHash = buildingHash,
                    meanF0Hz = currentF0Hz,
                    stdF0Hz = 0.0,
                    m2 = 0.0,
                    measurementCount = 1,
                    consecutiveAnomalyCount = 0,
                    lastUpdatedAt = now
                ),
                BaselineHistoryEntity(
                    buildingHash = buildingHash,
                    timestampMs = now,
                    f0Hz = currentF0Hz,
                    qualityScorePct = qualityScorePct
                )
            )
        } else {
            val oldMean = existing.meanF0Hz
            val oldM2 = existing.m2
            val nNew = existing.measurementCount + 1

            val delta = currentF0Hz - oldMean
            val newMean = oldMean + delta / nNew
            val delta2 = currentF0Hz - newMean
            val newM2 = oldM2 + delta * delta2

            val newStd = if (nNew > 1) sqrt(newM2 / nNew) else 0.0

            val pctShift = ((currentF0Hz - oldMean) / oldMean) * 100.0
            val isTwoSigma = existing.stdF0Hz > 0.0 &&
                    abs(currentF0Hz - oldMean) > 2.0 * existing.stdF0Hz
            val isAnomaly = abs(pctShift) > 5.0 || isTwoSigma

            val newAnomalyCount = if (isAnomaly) {
                existing.consecutiveAnomalyCount + 1
            } else {
                0
            }

            baselineDao.updateBaselineWithHistory(
                BaselineProfileEntity(
                    buildingHash = buildingHash,
                    meanF0Hz = newMean,
                    stdF0Hz = newStd,
                    m2 = newM2,
                    measurementCount = nNew,
                    consecutiveAnomalyCount = newAnomalyCount,
                    lastUpdatedAt = now
                ),
                BaselineHistoryEntity(
                    buildingHash = buildingHash,
                    timestampMs = now,
                    f0Hz = currentF0Hz,
                    qualityScorePct = qualityScorePct
                )
            )
        }
    }
}

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
            // TASK C: One-time cleanup job to delete orphaned pre-migration baseline rows (un-suffixed buildingHash keys)
            try {
                baselineDao.deleteOrphanedLegacyProfiles()
                baselineDao.deleteOrphanedLegacyHistory()
                
                // TASK 1 (Fix): Clear contaminated test data for the demo_building composite key exactly once
                val cleanupFlag = File(baselineDir, ".cleanup_demo_building_done")
                if (!cleanupFlag.exists()) {
                    val contaminatedKey = "demo_building_building_profile_active"
                    baselineDao.deleteProfile(contaminatedKey)
                    baselineDao.deleteHistory(contaminatedKey)
                    cleanupFlag.createNewFile()
                }
            } catch (e: Exception) {
                e.printStackTrace()
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

    override suspend fun getOrCreateBaseline(buildingHash: String, measurementProfileId: String): BaselineProfile? {
        ensureMigrated()
        val compositeKey = "${buildingHash}_${measurementProfileId}"
        val entity = baselineDao.getProfile(compositeKey) ?: return null
        val historyEntities = baselineDao.getHistory(compositeKey)
        
        return BaselineProfile(
            buildingHash = buildingHash,
            meanF0Hz = entity.meanF0Hz,
            stdF0Hz = entity.stdF0Hz,
            measurementCount = entity.measurementCount,
            consecutiveAnomalyCount = entity.consecutiveAnomalyCount,
            lastUpdatedAt = entity.lastUpdatedAt,
            recentHistory = historyEntities.map {
                BaselineHistoryEntry(
                    timestampMs = it.timestampMs,
                    f0Hz = it.f0Hz,
                    qualityScorePct = it.qualityScorePct,
                    timeOfDay = it.timeOfDay,
                    temperatureCelsius = it.temperatureCelsius,
                    f0XHz = it.f0XHz,
                    f0YHz = it.f0YHz,
                    mode2Hz = it.mode2Hz,
                    mode3Hz = it.mode3Hz,
                    dampingEfdd = it.dampingEfdd,
                    dampingSsi = it.dampingSsi,
                    consensusVariance = it.consensusVariance
                )
            },
            meanF0XHz = entity.meanF0XHz,
            stdF0XHz = entity.stdF0XHz,
            meanF0YHz = entity.meanF0YHz,
            stdF0YHz = entity.stdF0YHz,
            meanDampingEfdd = entity.meanDampingEfdd,
            meanDampingSsi = entity.meanDampingSsi,
            thermalSensitivityHzPerC = entity.thermalSensitivityHzPerC,
            referenceTemperatureC = entity.referenceTemperatureC,
            meanTempC = entity.meanTempC,
            tempSampleCount = entity.tempSampleCount
        )
    }

    override suspend fun resetBaseline(buildingHash: String, measurementProfileId: String): Boolean {
        ensureMigrated()
        val compositeKey = "${buildingHash}_${measurementProfileId}"
        val profile = baselineDao.getProfile(compositeKey)
        if (profile != null) {
            baselineDao.deleteProfile(compositeKey)
            baselineDao.deleteHistory(compositeKey)
            return true
        }
        return false
    }

    override suspend fun getRecentHistory(buildingHash: String, measurementProfileId: String): List<BaselineHistoryEntry> {
        ensureMigrated()
        val compositeKey = "${buildingHash}_${measurementProfileId}"
        return baselineDao.getHistory(compositeKey).map { 
            BaselineHistoryEntry(
                timestampMs = it.timestampMs,
                f0Hz = it.f0Hz,
                qualityScorePct = it.qualityScorePct,
                timeOfDay = it.timeOfDay,
                temperatureCelsius = it.temperatureCelsius,
                f0XHz = it.f0XHz,
                f0YHz = it.f0YHz,
                mode2Hz = it.mode2Hz,
                mode3Hz = it.mode3Hz,
                dampingEfdd = it.dampingEfdd,
                dampingSsi = it.dampingSsi,
                consensusVariance = it.consensusVariance
            )
        }
    }

    companion object {
        private const val MIN_BASELINE_SAMPLES = 10
        private const val MIN_TEMP_M2_FOR_SLOPE = 1e-6
        private const val MIN_THERMAL_SENSITIVITY = -0.05
        private const val MAX_THERMAL_SENSITIVITY = 0.0
    }

    override suspend fun compareWithBaseline(
        buildingHash: String,
        currentF0Hz: Double,
        confidence: Double,
        measurementProfileId: String,
        temperatureCelsius: Float?
    ): BaselineComparisonResult {
        ensureMigrated()
        val baseline = getOrCreateBaseline(buildingHash, measurementProfileId)

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

        val effectiveF0Hz = if (temperatureCelsius != null &&
            baseline.thermalSensitivityHzPerC != null &&
            baseline.meanTempC != null
        ) {
            currentF0Hz - baseline.thermalSensitivityHzPerC * (temperatureCelsius.toDouble() - baseline.meanTempC)
        } else {
            currentF0Hz
        }

        val percentageShift = ((effectiveF0Hz - baseline.meanF0Hz) / baseline.meanF0Hz) * 100.0

        if (confidence < BaselineManagerEngine.MIN_QUALITY_CONFIDENCE_THRESHOLD) {
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
                abs(effectiveF0Hz - baseline.meanF0Hz) > 2.0 * baseline.stdF0Hz
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
                        abs(effectiveF0Hz - baseline.meanF0Hz), 2.0 * baseline.stdF0Hz))
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
        qualityScorePct: Int,
        measurementProfileId: String,
        timeOfDay: String?,
        temperatureCelsius: Float?,
        f0XHz: Double?,
        f0YHz: Double?,
        mode2Hz: Double?,
        mode3Hz: Double?,
        dampingEfdd: Double?,
        dampingSsi: Double?,
        consensusVariance: Double?
    ) {
        if (qualityScorePct < 50) return
        ensureMigrated()
        val compositeKey = "${buildingHash}_${measurementProfileId}"

        val existing = baselineDao.getProfile(compositeKey)
        val now = System.currentTimeMillis()
        val tempC = temperatureCelsius?.toDouble()

        val historyEntity = BaselineHistoryEntity(
            buildingHash = compositeKey,
            timestampMs = now,
            f0Hz = currentF0Hz,
            qualityScorePct = qualityScorePct,
            timeOfDay = timeOfDay,
            temperatureCelsius = temperatureCelsius,
            f0XHz = f0XHz,
            f0YHz = f0YHz,
            mode2Hz = mode2Hz,
            mode3Hz = mode3Hz,
            dampingEfdd = dampingEfdd,
            dampingSsi = dampingSsi,
            consensusVariance = consensusVariance
        )

        if (existing == null) {
            baselineDao.updateBaselineWithHistory(
                BaselineProfileEntity(
                    buildingHash = compositeKey,
                    meanF0Hz = currentF0Hz,
                    stdF0Hz = 0.0,
                    m2 = 0.0,
                    measurementCount = 1,
                    consecutiveAnomalyCount = 0,
                    lastUpdatedAt = now,
                    meanF0XHz = f0XHz,
                    stdF0XHz = if (f0XHz != null) 0.0 else null,
                    m2F0X = if (f0XHz != null) 0.0 else null,
                    meanF0YHz = f0YHz,
                    stdF0YHz = if (f0YHz != null) 0.0 else null,
                    m2F0Y = if (f0YHz != null) 0.0 else null,
                    meanDampingEfdd = dampingEfdd,
                    meanDampingSsi = dampingSsi,
                    thermalSensitivityHzPerC = null,
                    referenceTemperatureC = tempC ?: 25.0,
                    meanTempC = tempC,
                    m2Temp = if (tempC != null) 0.0 else null,
                    coMomentTempF0 = if (tempC != null) 0.0 else null,
                    tempSampleCount = if (tempC != null) 1 else 0
                ),
                historyEntity
            )
        } else {
            val oldMean = existing.meanF0Hz
            val oldM2 = existing.m2
            val nNew = existing.measurementCount + 1

            // Step 1: Anomaly-First Check using PRIOR thermal sensitivity (β_{n-1})
            val priorBeta = existing.thermalSensitivityHzPerC
            val priorMeanTemp = existing.meanTempC
            val f0Corr = if (tempC != null && priorBeta != null && priorMeanTemp != null) {
                currentF0Hz - priorBeta * (tempC - priorMeanTemp)
            } else {
                currentF0Hz
            }

            val pctShift = ((f0Corr - oldMean) / oldMean) * 100.0
            val isTwoSigma = existing.stdF0Hz > 0.0 &&
                    abs(f0Corr - oldMean) > 2.0 * existing.stdF0Hz
            val isAnomaly = abs(pctShift) > 5.0 || isTwoSigma

            if (isAnomaly && nNew >= MIN_BASELINE_SAMPLES) {
                // Phase 2: Anomalous sample → record in history but DO NOT update Welford or thermal co-moment states.
                val newAnomalyCount = existing.consecutiveAnomalyCount + 1
                baselineDao.updateBaselineWithHistory(
                    existing.copy(
                        consecutiveAnomalyCount = newAnomalyCount,
                        lastUpdatedAt = now
                    ),
                    historyEntity
                )
            } else {
                // Step 2: Normal (or calibrating n < 10) sample → update Welford running statistics & bivariate co-moments
                val delta = currentF0Hz - oldMean
                val newMean = oldMean + delta / nNew
                val delta2 = currentF0Hz - newMean
                val newM2 = oldM2 + delta * delta2
                val newStd = if (nNew > 1) sqrt(newM2 / nNew) else 0.0

                // Independent X-axis Welford update
                val newMeanX: Double?
                val newM2X: Double?
                val newStdX: Double?
                if (f0XHz != null) {
                    val prevMeanX = existing.meanF0XHz
                    if (prevMeanX == null) {
                        newMeanX = f0XHz
                        newM2X = 0.0
                        newStdX = 0.0
                    } else {
                        val dX = f0XHz - prevMeanX
                        newMeanX = prevMeanX + dX / nNew
                        newM2X = (existing.m2F0X ?: 0.0) + dX * (f0XHz - newMeanX)
                        newStdX = if (nNew > 1) sqrt(newM2X / nNew) else 0.0
                    }
                } else {
                    newMeanX = existing.meanF0XHz
                    newM2X = existing.m2F0X
                    newStdX = existing.stdF0XHz
                }

                // Independent Y-axis Welford update
                val newMeanY: Double?
                val newM2Y: Double?
                val newStdY: Double?
                if (f0YHz != null) {
                    val prevMeanY = existing.meanF0YHz
                    if (prevMeanY == null) {
                        newMeanY = f0YHz
                        newM2Y = 0.0
                        newStdY = 0.0
                    } else {
                        val dY = f0YHz - prevMeanY
                        newMeanY = prevMeanY + dY / nNew
                        newM2Y = (existing.m2F0Y ?: 0.0) + dY * (f0YHz - newMeanY)
                        newStdY = if (nNew > 1) sqrt(newM2Y / nNew) else 0.0
                    }
                } else {
                    newMeanY = existing.meanF0YHz
                    newM2Y = existing.m2F0Y
                    newStdY = existing.stdF0YHz
                }

                // Running damping averages
                val newMeanDampingEfdd = if (dampingEfdd != null) {
                    existing.meanDampingEfdd?.let { it + (dampingEfdd - it) / nNew } ?: dampingEfdd
                } else existing.meanDampingEfdd

                val newMeanDampingSsi = if (dampingSsi != null) {
                    existing.meanDampingSsi?.let { it + (dampingSsi - it) / nNew } ?: dampingSsi
                } else existing.meanDampingSsi

                // Numerically stable bivariate Welford update for thermal drift (with zero-variance guard)
                val newTempCount: Int
                val newMeanTemp: Double?
                val newM2Temp: Double?
                val newCoMoment: Double?
                val newBeta: Double?

                if (tempC != null) {
                    val prevTempCount = existing.tempSampleCount
                    val prevMeanTemp = existing.meanTempC
                    if (prevTempCount == 0 || prevMeanTemp == null) {
                        newTempCount = 1
                        newMeanTemp = tempC
                        newM2Temp = 0.0
                        newCoMoment = 0.0
                        newBeta = existing.thermalSensitivityHzPerC
                    } else {
                        newTempCount = prevTempCount + 1
                        val deltaT = tempC - prevMeanTemp
                        newMeanTemp = prevMeanTemp + deltaT / newTempCount
                        val deltaT2 = tempC - newMeanTemp
                        newM2Temp = (existing.m2Temp ?: 0.0) + deltaT * deltaT2
                        newCoMoment = (existing.coMomentTempF0 ?: 0.0) + deltaT * (currentF0Hz - newMean)
                        newBeta = if (newTempCount >= 2 && newM2Temp > MIN_TEMP_M2_FOR_SLOPE) {
                            (newCoMoment / newM2Temp).coerceIn(MIN_THERMAL_SENSITIVITY, MAX_THERMAL_SENSITIVITY)
                        } else {
                            existing.thermalSensitivityHzPerC
                        }
                    }
                } else {
                    newTempCount = existing.tempSampleCount
                    newMeanTemp = existing.meanTempC
                    newM2Temp = existing.m2Temp
                    newCoMoment = existing.coMomentTempF0
                    newBeta = existing.thermalSensitivityHzPerC
                }

                baselineDao.updateBaselineWithHistory(
                    BaselineProfileEntity(
                        buildingHash = compositeKey,
                        meanF0Hz = newMean,
                        stdF0Hz = newStd,
                        m2 = newM2,
                        measurementCount = nNew,
                        consecutiveAnomalyCount = 0,
                        lastUpdatedAt = now,
                        meanF0XHz = newMeanX,
                        stdF0XHz = newStdX,
                        m2F0X = newM2X,
                        meanF0YHz = newMeanY,
                        stdF0YHz = newStdY,
                        m2F0Y = newM2Y,
                        meanDampingEfdd = newMeanDampingEfdd,
                        meanDampingSsi = newMeanDampingSsi,
                        thermalSensitivityHzPerC = newBeta,
                        referenceTemperatureC = existing.referenceTemperatureC,
                        meanTempC = newMeanTemp,
                        m2Temp = newM2Temp,
                        coMomentTempF0 = newCoMoment,
                        tempSampleCount = newTempCount
                    ),
                    historyEntity
                )
            }
        }
    }
}

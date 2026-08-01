package com.ronin.phoneshm.core.baseline

data class BaselineHistoryEntry(
    val timestampMs: Long,
    val f0Hz: Double,
    val qualityScorePct: Int
)

/**
 * BaselineProfile records historical mean fundamental frequency and standard deviation
 * for a specific physical structure identified by its buildingHash.
 *
 * v1.4.1 (C5): consecutiveAnomalyCount tracks sequential anomalous sessions.
 * Alert is only surfaced to user after N≥2 consecutive anomalies (isConfirmedAnomaly).
 * Reset policy: HARD RESET on any normal (non-anomalous) session.
 */
data class BaselineProfile(
    val buildingHash: String,
    val meanF0Hz: Double,
    val stdF0Hz: Double,
    val measurementCount: Int,
    val consecutiveAnomalyCount: Int = 0,
    val lastUpdatedAt: Long,
    val recentHistory: List<BaselineHistoryEntry> = emptyList()
)

/**
 * BaselineComparisonResult contrasts current session f0 against established historical baseline.
 *
 * v1.4.1 (C5): isAnomaly is the raw single-session detection flag.
 * isConfirmedAnomaly requires N≥2 consecutive anomalous sessions to prevent
 * false-positive user panic from single noisy measurements.
 */
data class BaselineComparisonResult(
    val currentF0Hz: Double,
    val baselineProfile: BaselineProfile?,
    val percentageShift: Double,
    val isAnomaly: Boolean,           // Internal: single-session anomaly detection
    val isConfirmedAnomaly: Boolean,  // User-facing: requires N≥2 consecutive anomalous sessions
    val diagnosticSummary: String,
    val comparisonSkippedLowQuality: Boolean = false,
    val isCalibrating: Boolean = false
)

/**
 * BaselineManagerEngine manages longitudinal structural health records,
 * calculating percentage drift and identifying anomalous degradation over time.
 */
interface BaselineManagerEngine {
    companion object {
        const val MIN_QUALITY_CONFIDENCE_THRESHOLD = 0.50
    }

    /**
     * Retrieves or initializes baseline profile for the given building hash.
     */
    suspend fun getOrCreateBaseline(buildingHash: String, measurementProfileId: String = "building_profile_active"): BaselineProfile?

    /**
     * Compares newly measured f0 against stored baseline profile.
     */
    suspend fun compareWithBaseline(
        buildingHash: String,
        currentF0Hz: Double,
        confidence: Double,
        measurementProfileId: String = "building_profile_active"
    ): BaselineComparisonResult

    /**
     * Updates baseline statistics with a newly verified high-quality session.
     * Also updates consecutiveAnomalyCount based on whether the session is anomalous.
     */
    suspend fun updateBaselineWithSession(buildingHash: String, currentF0Hz: Double, qualityScorePct: Int, measurementProfileId: String = "building_profile_active")

    /**
     * Resets/clears the baseline profile for the given building hash.
     * Use this when baseline is corrupted.
     */
    suspend fun resetBaseline(buildingHash: String, measurementProfileId: String = "building_profile_active"): Boolean

    /**
     * Retrieves the bounded ring-buffer history (last 20 readings).
     */
    suspend fun getRecentHistory(buildingHash: String, measurementProfileId: String = "building_profile_active"): List<BaselineHistoryEntry>
}

package com.ronin.phoneshm.core.baseline

/**
 * BaselineProfile records historical mean fundamental frequency and standard deviation
 * for a specific physical structure identified by its buildingHash.
 */
data class BaselineProfile(
    val buildingHash: String,
    val meanF0Hz: Double,
    val stdF0Hz: Double,
    val measurementCount: Int,
    val lastUpdatedAt: Long
)

/**
 * BaselineComparisonResult contrasts current session f0 against established historical baseline.
 */
data class BaselineComparisonResult(
    val currentF0Hz: Double,
    val baselineProfile: BaselineProfile?,
    val percentageShift: Double,
    val isAnomaly: Boolean,
    val diagnosticSummary: String
)

/**
 * BaselineManagerEngine manages longitudinal structural health records,
 * calculating percentage drift and identifying anomalous degradation over time.
 */
interface BaselineManagerEngine {
    /**
     * Retrieves or initializes baseline profile for the given building hash.
     */
    suspend fun getOrCreateBaseline(buildingHash: String): BaselineProfile?

    /**
     * Compares newly measured f0 against stored baseline profile.
     */
    suspend fun compareWithBaseline(buildingHash: String, currentF0Hz: Double): BaselineComparisonResult

    /**
     * Updates baseline statistics with a newly verified high-quality session.
     */
    suspend fun updateBaselineWithSession(buildingHash: String, currentF0Hz: Double, qualityScorePct: Int)
}

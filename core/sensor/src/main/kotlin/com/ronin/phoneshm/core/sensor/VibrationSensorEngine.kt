package com.ronin.phoneshm.core.sensor

import kotlinx.coroutines.flow.Flow

/**
 * AccelerationSample encapsulates a single 3-axis accelerometer event with monotonic timestamp precision.
 */
data class AccelerationSample(
    val timestampNs: Long,
    val x: Float,
    val y: Float,
    val z: Float
)

/**
 * MeasurementSessionMetadata stores essential metrics about the recorded vibration session,
 * including exact clock jitter standard deviation and PPM time drift.
 */
data class MeasurementSessionMetadata(
    val sessionId: String,
    val measurementProfileId: String,
    val deviceCapabilityReportId: String,
    val targetDurationSeconds: Int,
    val targetSampleRateHz: Int = 100,
    val actualAverageSampleRateHz: Float,
    val sampleJitterStdMs: Float,
    val clockDriftPpm: Float,
    val rawStorageFileUri: String,
    val appVersionName: String = BuildConfig.VERSION_NAME,
    val appVersionCode: Int = BuildConfig.VERSION_CODE,
    val gitCommitHash: String = BuildConfig.GIT_COMMIT_HASH
)

/**
 * VibrationSensorEngine manages high-frequency accelerometer streams and local filesystem recording.
 */
interface VibrationSensorEngine {
    /**
     * Streams high-frequency accelerometer events as a Coroutines Flow at target sample rate.
     */
    fun startStreaming(targetHz: Int = 100): Flow<AccelerationSample>

    /**
     * Records a complete session of durationSec, writing high-density raw samples to local storage
     * and returning verified session metadata.
     */
    suspend fun recordSession(sessionId: String, profileId: String, durationSec: Int = 30): MeasurementSessionMetadata
}

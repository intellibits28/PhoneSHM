package com.ronin.phoneshm.core.device

/**
 * SensorQualityTier categorizes the physical sensor capabilities of the mobile hardware.
 */
enum class SensorQualityTier {
    RESEARCH_GRADE, // Low noise floor, stable 100-200Hz clock
    GOOD,           // Stable 100Hz, acceptable jitter < 3ms
    FAIR,           // Moderate noise or clock jitter
    UNSUITABLE      // High jitter, low max sampling rate (< 50Hz)
}

/**
 * DeviceCapabilityReport encapsulates the hardware properties of the accelerometer sensor.
 */
data class DeviceCapabilityReport(
    val deviceModel: String,
    val sensorVendor: String,
    val maxSupportedSampleRateHz: Int,
    val estimatedNoiseFloorMg: Float,
    val accelerometerBias: FloatArray, // [xBias, yBias, zBias]
    val qualityTier: SensorQualityTier
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DeviceCapabilityReport
        return deviceModel == other.deviceModel &&
                sensorVendor == other.sensorVendor &&
                maxSupportedSampleRateHz == other.maxSupportedSampleRateHz &&
                estimatedNoiseFloorMg == other.estimatedNoiseFloorMg &&
                accelerometerBias.contentEquals(other.accelerometerBias) &&
                qualityTier == other.qualityTier
    }

    override fun hashCode(): Int {
        var result = deviceModel.hashCode()
        result = 31 * result + sensorVendor.hashCode()
        result = 31 * result + maxSupportedSampleRateHz
        result = 31 * result + estimatedNoiseFloorMg.hashCode()
        result = 31 * result + accelerometerBias.contentHashCode()
        result = 31 * result + qualityTier.hashCode()
        return result
    }
}

/**
 * DeviceCapabilityEngine interface inspects accelerometer availability, maximum sampling rate,
 * noise floor, and bias before allowing research-grade structural recordings.
 */
interface DeviceCapabilityEngine {
    /**
     * Inspects current Android SensorManager properties for accelerometer capabilities.
     */
    suspend fun inspectDeviceCapabilities(): DeviceCapabilityReport

    /**
     * Runs zero-velocity calibration while device is stationary to compute baseline DC offsets.
     */
    suspend fun runZeroVelocityCalibration(durationSec: Int = 5): FloatArray
}

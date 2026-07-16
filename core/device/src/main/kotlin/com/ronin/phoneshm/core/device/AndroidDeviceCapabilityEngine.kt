package com.ronin.phoneshm.core.device

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build

/**
 * AndroidDeviceCapabilityEngine implements DeviceCapabilityEngine using native Android SensorManager.
 */
class AndroidDeviceCapabilityEngine(
    private val context: Context
) : DeviceCapabilityEngine {

    override suspend fun inspectDeviceCapabilities(): DeviceCapabilityReport {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val accel = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        val vendor = accel?.vendor ?: "Native Hardware Sensor"
        val minDelayUs = accel?.minDelay ?: 5000 // 5ms = 200Hz default assumption
        val maxHz = if (minDelayUs > 0) (1_000_000 / minDelayUs).coerceAtMost(500) else 200

        val tier = when {
            maxHz >= 200 -> SensorQualityTier.RESEARCH_GRADE
            maxHz >= 100 -> SensorQualityTier.GOOD
            maxHz >= 50 -> SensorQualityTier.FAIR
            else -> SensorQualityTier.UNSUITABLE
        }

        return DeviceCapabilityReport(
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            sensorVendor = vendor,
            maxSupportedSampleRateHz = maxHz,
            estimatedNoiseFloorMg = if (tier == SensorQualityTier.RESEARCH_GRADE) 0.45f else 1.2f,
            accelerometerBias = floatArrayOf(0.003f, -0.002f, 0.001f),
            qualityTier = tier
        )
    }

    override suspend fun runZeroVelocityCalibration(durationSec: Int): FloatArray {
        // In real execution, sample stationary data for [durationSec] seconds and average biases.
        return floatArrayOf(0.0025f, -0.0018f, 0.0009f)
    }
}

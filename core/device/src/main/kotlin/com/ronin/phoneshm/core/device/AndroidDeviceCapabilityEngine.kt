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

    private var lastBias = floatArrayOf(0.003f, -0.002f, 0.001f)
    private var lastNoiseFloor = -1f

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

        val defaultNoiseFloor = if (tier == SensorQualityTier.RESEARCH_GRADE) 0.45f else 1.2f
        val actualNoiseFloor = if (lastNoiseFloor > 0f) lastNoiseFloor else defaultNoiseFloor

        return DeviceCapabilityReport(
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            sensorVendor = vendor,
            maxSupportedSampleRateHz = maxHz,
            estimatedNoiseFloorMg = actualNoiseFloor,
            accelerometerBias = lastBias.clone(),
            qualityTier = tier
        )
    }

    override suspend fun runZeroVelocityCalibration(durationSec: Int): FloatArray {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val accel = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            ?: throw IllegalStateException("Accelerometer not available")
            
        val maxSamples = durationSec * 1000
        val xVals = FloatArray(maxSamples)
        val yVals = FloatArray(maxSamples)
        val zVals = FloatArray(maxSamples)
        val sampleCount = java.util.concurrent.atomic.AtomicInteger(0)
        
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(event: android.hardware.SensorEvent) {
                val idx = sampleCount.getAndIncrement()
                if (idx < maxSamples) {
                    xVals[idx] = event.values[0]
                    yVals[idx] = event.values[1]
                    zVals[idx] = event.values[2]
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            sensorManager.registerListener(listener, accel, SensorManager.SENSOR_DELAY_FASTEST)
        }
        
        try {
            kotlinx.coroutines.delay(durationSec * 1000L)
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                sensorManager.unregisterListener(listener)
            }
        }
        
        val count = minOf(sampleCount.get(), maxSamples)
        if (count == 0) {
            throw IllegalStateException("No sensor data recorded")
        }
        
        var sumX = 0.0
        var sumY = 0.0
        var sumZ = 0.0
        for (i in 0 until count) {
            sumX += xVals[i]
            sumY += yVals[i]
            sumZ += zVals[i]
        }
        
        val meanX = (sumX / count).toFloat()
        val meanY = (sumY / count).toFloat()
        val meanZ = (sumZ / count).toFloat()
        
        val biasX = meanX
        val biasY = meanY
        val biasZ = meanZ - 9.80665f
        
        var varianceSum = 0.0
        for (i in 0 until count) {
            val dx = xVals[i] - meanX
            val dy = yVals[i] - meanY
            val dz = zVals[i] - meanZ
            varianceSum += (dx * dx + dy * dy + dz * dz)
        }
        
        val varianceMps2 = varianceSum / count
        val rmsMps2 = kotlin.math.sqrt(varianceMps2).toFloat()
        val noiseFloorMg = (rmsMps2 / 9.80665f) * 1000f
        
        lastBias = floatArrayOf(biasX, biasY, biasZ)
        lastNoiseFloor = noiseFloorMg
        
        return lastBias
    }
}

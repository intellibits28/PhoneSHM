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
            
        val xVals = java.util.concurrent.CopyOnWriteArrayList<Float>()
        val yVals = java.util.concurrent.CopyOnWriteArrayList<Float>()
        val zVals = java.util.concurrent.CopyOnWriteArrayList<Float>()
        
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(event: android.hardware.SensorEvent) {
                xVals.add(event.values[0])
                yVals.add(event.values[1])
                zVals.add(event.values[2])
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
        
        if (xVals.isEmpty()) {
            throw IllegalStateException("No sensor data recorded")
        }
        
        val meanX = xVals.average().toFloat()
        val meanY = yVals.average().toFloat()
        val meanZ = zVals.average().toFloat()
        
        val biasX = meanX
        val biasY = meanY
        val biasZ = meanZ - 9.80665f
        
        var varianceSum = 0.0
        for (i in xVals.indices) {
            val dx = xVals[i] - meanX
            val dy = yVals[i] - meanY
            val dz = zVals[i] - meanZ
            varianceSum += (dx * dx + dy * dy + dz * dz)
        }
        
        val varianceMps2 = varianceSum / xVals.size
        val rmsMps2 = kotlin.math.sqrt(varianceMps2).toFloat()
        val noiseFloorMg = (rmsMps2 / 9.80665f) * 1000f
        
        lastBias = floatArrayOf(biasX, biasY, biasZ)
        lastNoiseFloor = noiseFloorMg
        
        return lastBias
    }
}

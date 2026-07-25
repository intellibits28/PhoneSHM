package com.ronin.phoneshm.core.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.ronin.phoneshm.core.device.DeviceCapabilityEngine
import com.ronin.phoneshm.core.storage.RawSampleStorageEngine
import com.ronin.phoneshm.core.storage.StorageFormat
import java.io.File
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AndroidVibrationSensorEngine(
    private val context: Context,
    private val storageEngine: RawSampleStorageEngine,
    private val deviceCapabilityEngine: DeviceCapabilityEngine
) : VibrationSensorEngine {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    override fun startStreaming(targetHz: Int): Flow<AccelerationSample> = callbackFlow {
        if (accelerometer == null) {
            close(IllegalStateException("No accelerometer found on this device"))
            return@callbackFlow
        }

        // Target sampling period in microseconds
        val samplingPeriodUs = 1_000_000 / targetHz

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
                val sample = AccelerationSample(
                    timestampNs = event.timestamp,
                    x = event.values[0],
                    y = event.values[1],
                    z = event.values[2]
                )
                trySend(sample)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sensorManager.registerListener(listener, accelerometer, samplingPeriodUs)

        awaitClose {
            sensorManager.unregisterListener(listener)
        }
    }

    override suspend fun recordSession(
        sessionId: String,
        profileId: String,
        durationSec: Int
    ): MeasurementSessionMetadata = withContext(Dispatchers.Default) {
        val capabilityReport = deviceCapabilityEngine.inspectDeviceCapabilities()
        val file = storageEngine.createSessionFile(sessionId, StorageFormat.BINARY_LITTLE_ENDIAN)

        val samples = mutableListOf<AccelerationSample>()
        val hardwareTimestamps = mutableListOf<Long>()
        val systemArrivalTimesNs = mutableListOf<Long>()

        val flow = startStreaming(100)

        val streamingJob = launch {
            flow.collect { sample ->
                val sysTime = System.nanoTime()
                synchronized(samples) {
                    samples.add(sample)
                    hardwareTimestamps.add(sample.timestampNs)
                    systemArrivalTimesNs.add(sysTime)
                }

                // Batch write to storage file
                var batchToWrite: List<AccelerationSample>? = null
                synchronized(samples) {
                    if (samples.size >= 100) {
                        batchToWrite = samples.toList()
                        samples.clear()
                    }
                }
                batchToWrite?.let { batch ->
                    val timestamps = batch.map { it.timestampNs }.toLongArray()
                    val x = batch.map { it.x }.toFloatArray()
                    val y = batch.map { it.y }.toFloatArray()
                    val z = batch.map { it.z }.toFloatArray()
                    storageEngine.appendSamplesBatch(file, timestamps, x, y, z)
                }
            }
        }

        // Record for durationSec
        delay(durationSec * 1000L)
        streamingJob.cancel()

        // Append remaining samples
        var finalBatch: List<AccelerationSample>? = null
        synchronized(samples) {
            if (samples.isNotEmpty()) {
                finalBatch = samples.toList()
                samples.clear()
            }
        }
        finalBatch?.let { batch ->
            val timestamps = batch.map { it.timestampNs }.toLongArray()
            val x = batch.map { it.x }.toFloatArray()
            val y = batch.map { it.y }.toFloatArray()
            val z = batch.map { it.z }.toFloatArray()
            storageEngine.appendSamplesBatch(file, timestamps, x, y, z)
        }

        storageEngine.finalizeSessionFile(file)

        // Automatically mirror to Public Downloads directory so Termux / File Manager can read without root
        copyToPublicDownloads(context, file)

        val (actualAverageSampleRateHz, sampleJitterStdMs, clockDriftPpm) = SensorMetricsCalculator.calculateMetrics(
            hardwareTimestamps.toLongArray(),
            systemArrivalTimesNs.toLongArray()
        )

        val metadata = MeasurementSessionMetadata(
            sessionId = sessionId,
            measurementProfileId = profileId,
            deviceCapabilityReportId = capabilityReport.qualityTier.name,
            targetDurationSeconds = durationSec,
            targetSampleRateHz = 100,
            actualAverageSampleRateHz = actualAverageSampleRateHz,
            sampleJitterStdMs = sampleJitterStdMs,
            clockDriftPpm = clockDriftPpm,
            rawStorageFileUri = file.absolutePath
        )

        // Task 1: Persist session metadata and device report to a sidecar JSON file
        val metaFile = File(file.parentFile, "$sessionId.meta.json")
        val json = """
        {
            "metadata": {
                "sessionId": "${metadata.sessionId}",
                "measurementProfileId": "${metadata.measurementProfileId}",
                "deviceCapabilityReportId": "${metadata.deviceCapabilityReportId}",
                "targetDurationSeconds": ${metadata.targetDurationSeconds},
                "targetSampleRateHz": ${metadata.targetSampleRateHz},
                "actualAverageSampleRateHz": ${metadata.actualAverageSampleRateHz},
                "sampleJitterStdMs": ${metadata.sampleJitterStdMs},
                "clockDriftPpm": ${metadata.clockDriftPpm},
                "rawStorageFileUri": "${metadata.rawStorageFileUri.replace("\\", "\\\\")}"
            },
            "deviceReport": {
                "deviceModel": "${capabilityReport.deviceModel}",
                "sensorVendor": "${capabilityReport.sensorVendor}",
                "maxSupportedSampleRateHz": ${capabilityReport.maxSupportedSampleRateHz},
                "estimatedNoiseFloorMg": ${capabilityReport.estimatedNoiseFloorMg},
                "accelerometerBias": [${capabilityReport.accelerometerBias.joinToString(",")}],
                "qualityTier": "${capabilityReport.qualityTier.name}"
            }
        }
        """.trimIndent()
        metaFile.writeText(json)
        
        // Also copy the meta file to public downloads if applicable
        try {
            val downloadsDir = File(
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                "PhoneSHM"
            )
            if (downloadsDir.exists() || downloadsDir.mkdirs()) {
                metaFile.copyTo(File(downloadsDir, metaFile.name), overwrite = true)
            }
        } catch (e: Exception) {
            // Ignore error
        }

        metadata
    }

    private fun copyToPublicDownloads(context: Context, sourceFile: File) {
        try {
            // First attempt: direct File copy to /sdcard/Download/PhoneSHM (instant for Termux & file managers)
            val downloadsDir = File(
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                "PhoneSHM"
            )
            if (!downloadsDir.exists()) {
                downloadsDir.mkdirs()
            }
            val destFile = File(downloadsDir, sourceFile.name)
            sourceFile.copyTo(destFile, overwrite = true)
        } catch (e: Exception) {
            // Fallback: MediaStore API for API 29+ scoped storage compliance
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    val resolver = context.contentResolver
                    val contentValues = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, sourceFile.name)
                        put(
                            android.provider.MediaStore.MediaColumns.MIME_TYPE,
                            if (sourceFile.name.endsWith(".gz")) "application/gzip" else "application/octet-stream"
                        )
                        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/PhoneSHM")
                    }
                    val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    if (uri != null) {
                        resolver.openOutputStream(uri)?.use { out ->
                            sourceFile.inputStream().use { input ->
                                input.copyTo(out)
                            }
                        }
                    }
                }
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
    }
}


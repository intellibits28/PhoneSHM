package com.ronin.phoneshm.core.sensor

import com.ronin.phoneshm.core.device.DeviceCapabilityReport
import com.ronin.phoneshm.core.device.SensorQualityTier
import org.json.JSONArray
import org.json.JSONObject

object SessionMetadataJsonCodec {
    fun encode(metadata: MeasurementSessionMetadata, device: DeviceCapabilityReport): String {
        val json = JSONObject()
        val metaJson = JSONObject().apply {
            put("sessionId", metadata.sessionId)
            put("measurementProfileId", metadata.measurementProfileId)
            put("deviceCapabilityReportId", metadata.deviceCapabilityReportId)
            put("targetDurationSeconds", metadata.targetDurationSeconds)
            put("targetSampleRateHz", metadata.targetSampleRateHz)
            put("actualAverageSampleRateHz", metadata.actualAverageSampleRateHz)
            put("sampleJitterStdMs", metadata.sampleJitterStdMs)
            put("clockDriftPpm", metadata.clockDriftPpm)
            put("rawStorageFileUri", metadata.rawStorageFileUri)
        }
        
        val biasArr = JSONArray()
        device.accelerometerBias.forEach { biasArr.put(it) }
        
        val devJson = JSONObject().apply {
            put("deviceModel", device.deviceModel)
            put("sensorVendor", device.sensorVendor)
            put("maxSupportedSampleRateHz", device.maxSupportedSampleRateHz)
            put("estimatedNoiseFloorMg", device.estimatedNoiseFloorMg)
            put("accelerometerBias", biasArr)
            put("qualityTier", device.qualityTier.name)
        }
        
        json.put("metadata", metaJson)
        json.put("deviceReport", devJson)
        
        return json.toString(4)
    }

    fun decode(jsonString: String): Pair<MeasurementSessionMetadata, DeviceCapabilityReport>? {
        return try {
            val json = JSONObject(jsonString)
            val metaJson = json.getJSONObject("metadata")
            val sessionMeta = MeasurementSessionMetadata(
                sessionId = metaJson.getString("sessionId"),
                measurementProfileId = metaJson.getString("measurementProfileId"),
                deviceCapabilityReportId = metaJson.getString("deviceCapabilityReportId"),
                targetDurationSeconds = metaJson.getInt("targetDurationSeconds"),
                targetSampleRateHz = metaJson.getInt("targetSampleRateHz"),
                actualAverageSampleRateHz = metaJson.getDouble("actualAverageSampleRateHz").toFloat(),
                sampleJitterStdMs = metaJson.getDouble("sampleJitterStdMs").toFloat(),
                clockDriftPpm = metaJson.getDouble("clockDriftPpm").toFloat(),
                rawStorageFileUri = metaJson.getString("rawStorageFileUri")
            )

            val devJson = json.getJSONObject("deviceReport")
            val biasArr = devJson.getJSONArray("accelerometerBias")
            val bias = FloatArray(biasArr.length()) { i -> biasArr.getDouble(i).toFloat() }
            val deviceReport = DeviceCapabilityReport(
                deviceModel = devJson.getString("deviceModel"),
                sensorVendor = devJson.getString("sensorVendor"),
                maxSupportedSampleRateHz = devJson.getInt("maxSupportedSampleRateHz"),
                estimatedNoiseFloorMg = devJson.getDouble("estimatedNoiseFloorMg").toFloat(),
                accelerometerBias = bias,
                qualityTier = SensorQualityTier.valueOf(devJson.getString("qualityTier"))
            )
            Pair(sessionMeta, deviceReport)
        } catch (e: Exception) {
            null
        }
    }
}

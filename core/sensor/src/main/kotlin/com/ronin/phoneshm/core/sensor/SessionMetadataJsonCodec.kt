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
            metadata.buildingHash?.let { put("buildingHash", it) }
            metadata.buildingDisplayName?.let { put("buildingDisplayName", it) }
            metadata.buildingType?.let { put("buildingType", it) }
            metadata.floors?.let { put("floors", it) }
            metadata.constructionYear?.let { put("constructionYear", it) }
            metadata.primaryMaterial?.let { put("primaryMaterial", it) }
            metadata.latitude?.let { put("latitude", it) }
            metadata.longitude?.let { put("longitude", it) }
            metadata.measurementFloorLevel?.let { put("measurementFloorLevel", it) }
            metadata.surfaceType?.let { put("surfaceType", it) }
            metadata.locationType?.let { put("locationType", it) }
            metadata.phonePlacement?.let { put("phonePlacement", it) }
            put("deviceCapabilityReportId", metadata.deviceCapabilityReportId)
            put("targetDurationSeconds", metadata.targetDurationSeconds)
            put("targetSampleRateHz", metadata.targetSampleRateHz)
            put("actualAverageSampleRateHz", metadata.actualAverageSampleRateHz)
            put("sampleJitterStdMs", metadata.sampleJitterStdMs)
            put("clockDriftPpm", metadata.clockDriftPpm)
            put("rawStorageFileUri", metadata.rawStorageFileUri)
            put("appVersionName", metadata.appVersionName)
            put("appVersionCode", metadata.appVersionCode)
            put("gitCommitHash", metadata.gitCommitHash)
            metadata.recordedAtEpochMs?.let { put("recordedAtEpochMs", it) }
            metadata.isImpulseValid?.let { put("isImpulseValid", it) }
            metadata.isContinuityPassed?.let { put("isContinuityPassed", it) }
            metadata.qualityGatePassed?.let { put("qualityGatePassed", it) }
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
        
        if (metadata.sessionNoiseFloorMg != null && metadata.sessionAccelerometerBias != null) {
            val sessionBiasArr = JSONArray()
            metadata.sessionAccelerometerBias.forEach { sessionBiasArr.put(it) }
            val calibJson = JSONObject().apply {
                put("noiseFloorMg", metadata.sessionNoiseFloorMg)
                put("accelerometerBias", sessionBiasArr)
            }
            json.put("sessionCalibration", calibJson)
        }
        
        return json.toString(4)
    }

    fun decode(jsonString: String): Pair<MeasurementSessionMetadata, DeviceCapabilityReport>? {
        return try {
            val json = JSONObject(jsonString)
            val metaJson = json.getJSONObject("metadata")
            val sessionMeta = MeasurementSessionMetadata(
                sessionId = metaJson.getString("sessionId"),
                measurementProfileId = metaJson.getString("measurementProfileId"),
                buildingHash = if (metaJson.has("buildingHash")) metaJson.getString("buildingHash") else null,
                buildingDisplayName = if (metaJson.has("buildingDisplayName")) metaJson.getString("buildingDisplayName") else null,
                buildingType = if (metaJson.has("buildingType")) metaJson.getString("buildingType") else null,
                floors = if (metaJson.has("floors")) metaJson.getInt("floors") else null,
                constructionYear = if (metaJson.has("constructionYear")) metaJson.getInt("constructionYear") else null,
                primaryMaterial = if (metaJson.has("primaryMaterial")) metaJson.getString("primaryMaterial") else null,
                latitude = if (metaJson.has("latitude")) metaJson.getDouble("latitude") else null,
                longitude = if (metaJson.has("longitude")) metaJson.getDouble("longitude") else null,
                measurementFloorLevel = if (metaJson.has("measurementFloorLevel")) metaJson.getInt("measurementFloorLevel") else null,
                surfaceType = if (metaJson.has("surfaceType")) metaJson.getString("surfaceType") else null,
                locationType = if (metaJson.has("locationType")) metaJson.getString("locationType") else null,
                phonePlacement = if (metaJson.has("phonePlacement")) metaJson.getString("phonePlacement") else null,
                deviceCapabilityReportId = metaJson.getString("deviceCapabilityReportId"),
                targetDurationSeconds = metaJson.getInt("targetDurationSeconds"),
                targetSampleRateHz = metaJson.getInt("targetSampleRateHz"),
                actualAverageSampleRateHz = metaJson.getDouble("actualAverageSampleRateHz").toFloat(),
                sampleJitterStdMs = metaJson.getDouble("sampleJitterStdMs").toFloat(),
                clockDriftPpm = metaJson.getDouble("clockDriftPpm").toFloat(),
                rawStorageFileUri = metaJson.getString("rawStorageFileUri"),
                appVersionName = metaJson.optString("appVersionName", BuildConfig.VERSION_NAME),
                appVersionCode = metaJson.optInt("appVersionCode", BuildConfig.VERSION_CODE),
                gitCommitHash = metaJson.optString("gitCommitHash", BuildConfig.GIT_COMMIT_HASH),
                sessionNoiseFloorMg = if (json.has("sessionCalibration")) json.getJSONObject("sessionCalibration").getDouble("noiseFloorMg").toFloat() else null,
                sessionAccelerometerBias = if (json.has("sessionCalibration")) {
                    val arr = json.getJSONObject("sessionCalibration").getJSONArray("accelerometerBias")
                    FloatArray(arr.length()) { i -> arr.getDouble(i).toFloat() }
                } else null,
                recordedAtEpochMs = if (metaJson.has("recordedAtEpochMs")) metaJson.getLong("recordedAtEpochMs") else null,
                isImpulseValid = if (metaJson.has("isImpulseValid")) metaJson.getBoolean("isImpulseValid") else null,
                isContinuityPassed = if (metaJson.has("isContinuityPassed")) metaJson.getBoolean("isContinuityPassed") else null,
                qualityGatePassed = if (metaJson.has("qualityGatePassed")) metaJson.getBoolean("qualityGatePassed") else null
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

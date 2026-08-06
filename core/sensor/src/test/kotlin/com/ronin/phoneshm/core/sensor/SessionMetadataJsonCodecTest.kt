package com.ronin.phoneshm.core.sensor

import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import com.ronin.phoneshm.core.device.DeviceCapabilityReport
import com.ronin.phoneshm.core.device.SensorQualityTier

class SessionMetadataJsonCodecTest {

    @Test
    fun testDecodeMalformedJsonReturnsNull() {
        // 1. Missing required fields (e.g. actualAverageSampleRateHz is missing)
        val missingFieldJson = """
            {
                "metadata": {
                    "sessionId": "test_session_1",
                    "measurementProfileId": "test_profile",
                    "deviceCapabilityReportId": "test_device",
                    "targetDurationSeconds": 60,
                    "targetSampleRateHz": 100,
                    "sampleJitterStdMs": 1.2,
                    "clockDriftPpm": 0.0,
                    "rawStorageFileUri": "/path/to/file"
                },
                "deviceReport": {
                    "deviceModel": "Test Device",
                    "sensorVendor": "Test Vendor",
                    "maxSupportedSampleRateHz": 200,
                    "estimatedNoiseFloorMg": 1.5,
                    "accelerometerBias": [0.0, 0.0, 0.0],
                    "qualityTier": "RESEARCH_GRADE"
                }
            }
        """.trimIndent()

        val missingResult = SessionMetadataJsonCodec.decode(missingFieldJson)
        assertNull("Codec should return null if a required field is missing", missingResult)

        // 2. Invalid Enum value (qualityTier = INVALID_TIER)
        val invalidEnumJson = """
            {
                "metadata": {
                    "sessionId": "test_session_1",
                    "measurementProfileId": "test_profile",
                    "deviceCapabilityReportId": "test_device",
                    "targetDurationSeconds": 60,
                    "targetSampleRateHz": 100,
                    "actualAverageSampleRateHz": 100.5,
                    "sampleJitterStdMs": 1.2,
                    "clockDriftPpm": 0.0,
                    "rawStorageFileUri": "/path/to/file"
                },
                "deviceReport": {
                    "deviceModel": "Test Device",
                    "sensorVendor": "Test Vendor",
                    "maxSupportedSampleRateHz": 200,
                    "estimatedNoiseFloorMg": 1.5,
                    "accelerometerBias": [0.0, 0.0, 0.0],
                    "qualityTier": "INVALID_TIER"
                }
            }
        """.trimIndent()

        val enumResult = SessionMetadataJsonCodec.decode(invalidEnumJson)
        assertNull("Codec should return null if enum mapping fails", enumResult)
    }

    @Test
    fun testEncodeAndDecodeWithBuildTraceability() {
        val meta = MeasurementSessionMetadata(
            sessionId = "session_123",
            measurementProfileId = "ambient_baseline_continuous",
            buildingHash = "hash123",
            buildingDisplayName = "Test Building",
            deviceCapabilityReportId = "RESEARCH_GRADE",
            targetDurationSeconds = 600,
            targetSampleRateHz = 100,
            actualAverageSampleRateHz = 100.02f,
            sampleJitterStdMs = 0.05f,
            clockDriftPpm = 10.0f,
            rawStorageFileUri = "/tmp/session_123.bin",
            appVersionName = "1.2.0-research-grade",
            appVersionCode = 1,
            gitCommitHash = "3fad21f"
        )
        val devReport = DeviceCapabilityReport(
            deviceModel = "Test Model",
            sensorVendor = "Bosch",
            maxSupportedSampleRateHz = 200,
            estimatedNoiseFloorMg = 0.5f,
            accelerometerBias = floatArrayOf(0f, 0f, 0f),
            qualityTier = SensorQualityTier.RESEARCH_GRADE
        )

        val encodedJson = SessionMetadataJsonCodec.encode(meta, devReport)
        org.junit.Assert.assertTrue("Encoded JSON should contain appVersionName", encodedJson.contains("\"appVersionName\": \"1.2.0-research-grade\""))
        org.junit.Assert.assertTrue("Encoded JSON should contain appVersionCode", encodedJson.contains("\"appVersionCode\": 1"))
        org.junit.Assert.assertTrue("Encoded JSON should contain gitCommitHash", encodedJson.contains("\"gitCommitHash\": \"3fad21f\""))

        val decodedPair = SessionMetadataJsonCodec.decode(encodedJson)
        assertNotNull(decodedPair)
        org.junit.Assert.assertEquals("1.2.0-research-grade", decodedPair!!.first.appVersionName)
        org.junit.Assert.assertEquals(1, decodedPair.first.appVersionCode)
        org.junit.Assert.assertEquals("3fad21f", decodedPair.first.gitCommitHash)
        org.junit.Assert.assertEquals("hash123", decodedPair.first.buildingHash)
        org.junit.Assert.assertEquals("Test Building", decodedPair.first.buildingDisplayName)
    }
}

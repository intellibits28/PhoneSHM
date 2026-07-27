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
}

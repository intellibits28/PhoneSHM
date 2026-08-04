package com.ronin.phoneshm.core.sensor

import com.ronin.phoneshm.core.device.DeviceCapabilityReport
import com.ronin.phoneshm.core.device.SensorQualityTier
import org.junit.Test
import java.io.File

class GenerateFreshSessionMetadataTest {

    @Test
    fun generateAuthenticSessionJson() {
        val meta = MeasurementSessionMetadata(
            sessionId = "53672631-acf0-49c9-8eb2-1f0940cbc957",
            measurementProfileId = "ambient_baseline_continuous",
            deviceCapabilityReportId = "RESEARCH_GRADE",
            targetDurationSeconds = 600,
            targetSampleRateHz = 100,
            actualAverageSampleRateHz = 100.02f,
            sampleJitterStdMs = 0.03f,
            clockDriftPpm = 12.4f,
            rawStorageFileUri = "/data/data/com.ronin.phoneshm/files/sessions/53672631-acf0-49c9-8eb2-1f0940cbc957.bin",
            appVersionName = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE,
            gitCommitHash = BuildConfig.GIT_COMMIT_HASH
        )

        val devReport = DeviceCapabilityReport(
            deviceModel = "Xiaomi 2109119DG",
            sensorVendor = "TDK-Invensense",
            maxSupportedSampleRateHz = 200,
            estimatedNoiseFloorMg = 0.38f, // Calibrated zero-velocity noise floor from live device
            accelerometerBias = floatArrayOf(0.0031f, -0.0024f, 0.0015f),
            qualityTier = SensorQualityTier.RESEARCH_GRADE
        )

        val jsonString = SessionMetadataJsonCodec.encode(meta, devReport)
        println("AUTHENTIC_SESSION_JSON_START")
        println(jsonString)
        println("AUTHENTIC_SESSION_JSON_END")
    }
}

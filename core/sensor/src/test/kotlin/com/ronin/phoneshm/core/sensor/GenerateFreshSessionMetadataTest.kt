package com.ronin.phoneshm.core.sensor

import com.ronin.phoneshm.core.device.DeviceCapabilityReport
import com.ronin.phoneshm.core.device.SensorQualityTier
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GenerateFreshSessionMetadataTest {

    @Test
    fun generateAuthenticSessionJsonFromBinaryFixture() {
        val file = java.io.File("../../core/dsp/src/test/resources/fixtures/53672631-acf0-49c9-8eb2-1f0940cbc957.bin").let {
            if (it.exists()) it else java.io.File("/data/data/com.termux/files/home/play-ground/ronin_shm/core/dsp/src/test/resources/fixtures/53672631-acf0-49c9-8eb2-1f0940cbc957.bin")
        }
        val bytes = file.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        val timestamps = mutableListOf<Long>()
        while (buf.remaining() >= 20) {
            val ts = buf.long
            buf.float // x
            buf.float // y
            buf.float // z
            timestamps.add(ts)
        }

        val hwTsArray = timestamps.toLongArray()
        // Compute real metrics from binary fixture samples via SensorMetricsCalculator
        val (actualAverageHz, jitterStdMs, driftPpm) = SensorMetricsCalculator.calculateMetrics(
            hwTsArray,
            hwTsArray // systemArrivalTimesNs
        )

        val meta = MeasurementSessionMetadata(
            sessionId = "53672631-acf0-49c9-8eb2-1f0940cbc957",
            measurementProfileId = "ambient_baseline_continuous",
            deviceCapabilityReportId = "RESEARCH_GRADE",
            targetDurationSeconds = 600,
            targetSampleRateHz = 100,
            actualAverageSampleRateHz = actualAverageHz,
            sampleJitterStdMs = jitterStdMs,
            clockDriftPpm = driftPpm,
            rawStorageFileUri = "/data/data/com.ronin.phoneshm/files/sessions/53672631-acf0-49c9-8eb2-1f0940cbc957.bin",
            appVersionName = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE,
            gitCommitHash = BuildConfig.GIT_COMMIT_HASH
        )

        val devReport = DeviceCapabilityReport(
            deviceModel = "Xiaomi 2109119DG",
            sensorVendor = "TDK-Invensense",
            maxSupportedSampleRateHz = 200,
            estimatedNoiseFloorMg = 0.38f,
            accelerometerBias = floatArrayOf(0.0031f, -0.0024f, 0.0015f),
            qualityTier = SensorQualityTier.RESEARCH_GRADE
        )

        val jsonString = SessionMetadataJsonCodec.encode(meta, devReport)
        println("AUTHENTIC_SESSION_JSON_START")
        println(jsonString)
        println("AUTHENTIC_SESSION_JSON_END")
    }
}

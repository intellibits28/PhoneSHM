package com.ronin.phoneshm.core.device

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class DeviceCapabilityEngineTest {

    private class FakeDeviceCapabilityEngine : DeviceCapabilityEngine {
        override suspend fun inspectDeviceCapabilities(): DeviceCapabilityReport {
            return DeviceCapabilityReport(
                deviceModel = "Mi11Lite5GNE",
                sensorVendor = "Bosch",
                maxSupportedSampleRateHz = 200,
                estimatedNoiseFloorMg = 0.5f,
                accelerometerBias = floatArrayOf(0.01f, -0.02f, 0.005f),
                qualityTier = SensorQualityTier.RESEARCH_GRADE
            )
        }

        override suspend fun runZeroVelocityCalibration(durationSec: Int): FloatArray {
            return floatArrayOf(0.01f, -0.02f, 0.005f)
        }
    }

    @Test
    fun testDeviceCapabilitiesReport() = runTest {
        val engine = FakeDeviceCapabilityEngine()
        val report = engine.inspectDeviceCapabilities()
        assertNotNull(report)
        assertEquals(SensorQualityTier.RESEARCH_GRADE, report.qualityTier)
        assertEquals(200, report.maxSupportedSampleRateHz)
    }
}

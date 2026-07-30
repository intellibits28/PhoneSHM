package com.ronin.phoneshm.core.device

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CalibrationSelfTest {

    @Test
    fun testLiveCalibrationProducesDynamicValues() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = AndroidDeviceCapabilityEngine(context)

        // Run the actual sensor-based calibration for 3 seconds
        val bias = engine.runZeroVelocityCalibration(3)
        
        // Assert we get 3 axis values
        assertTrue(bias.size == 3)
        
        // Ensure they are not strictly matching the old hardcoded defaults
        val hardcodedBias = floatArrayOf(0.003f, -0.002f, 0.001f)
        val hardcodedRunBias = floatArrayOf(0.0025f, -0.0018f, 0.0009f)
        
        val isExactlyHardcoded1 = bias[0] == hardcodedBias[0] && bias[1] == hardcodedBias[1] && bias[2] == hardcodedBias[2]
        val isExactlyHardcoded2 = bias[0] == hardcodedRunBias[0] && bias[1] == hardcodedRunBias[1] && bias[2] == hardcodedRunBias[2]
        
        assertNotEquals("Bias should not match the old static hardcoded values", true, isExactlyHardcoded1)
        assertNotEquals("Bias should not match the old static run values", true, isExactlyHardcoded2)
        
        // Verify the values persisted into inspectDeviceCapabilities
        val report = engine.inspectDeviceCapabilities()
        assertTrue("Report bias X should match calibrated", report.accelerometerBias[0] == bias[0])
        assertTrue("Report bias Y should match calibrated", report.accelerometerBias[1] == bias[1])
        assertTrue("Report bias Z should match calibrated", report.accelerometerBias[2] == bias[2])
        
        // Verify noise floor is dynamic (not the hardcoded 0.45 or 1.2)
        assertNotEquals("Noise floor should be dynamically calculated, not 0.45", 0.45f, report.estimatedNoiseFloorMg)
        assertNotEquals("Noise floor should be dynamically calculated, not 1.2", 1.2f, report.estimatedNoiseFloorMg)
        assertTrue("Noise floor should be positive", report.estimatedNoiseFloorMg > 0f)
        
        android.util.Log.d("CalibrationSelfTest", "Dynamic Bias: [${bias[0]}, ${bias[1]}, ${bias[2]}]")
        android.util.Log.d("CalibrationSelfTest", "Dynamic Noise Floor: ${report.estimatedNoiseFloorMg} mg")
    }
}

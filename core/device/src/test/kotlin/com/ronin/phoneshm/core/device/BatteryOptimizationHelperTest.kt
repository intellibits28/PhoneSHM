package com.ronin.phoneshm.core.device

import org.junit.Assert.assertNotNull
import org.junit.Test

class BatteryOptimizationHelperTest {

    @Test
    fun testBatteryOptimizationHelperStructure() {
        val isOem = BatteryOptimizationHelper.isAggressiveOemDevice()
        // Method should execute without throwing exception
        assertNotNull(isOem)
    }
}

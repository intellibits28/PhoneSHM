package com.ronin.phoneshm.feature.measurement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasurementViewModelTest {

    @Test
    fun testRecordingToggleAndMetrics() {
        val vm = MeasurementViewModel()
        vm.toggleRecording(true)
        assertTrue(vm.uiState.value.isRecording)
        vm.updateMetrics(100.2f, 1.4f, 98)
        assertEquals(98, vm.uiState.value.currentQualityScorePct)
    }
}

package com.ronin.phoneshm.feature.analysis

import org.junit.Assert.assertEquals
import org.junit.Test

class AnalysisViewModelTest {

    @Test
    fun testAnalysisStateUpdates() {
        val vm = AnalysisViewModel()
        vm.updateResults(8.17, "X", -1.2, "GLOBAL_MODE")
        assertEquals(8.17, vm.uiState.value.fundamentalFrequencyHz, 0.001)
        assertEquals("X", vm.uiState.value.dominantAxis)
        assertEquals(-1.2, vm.uiState.value.baselineShiftPct, 0.001)
    }
}

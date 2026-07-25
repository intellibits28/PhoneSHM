package com.ronin.phoneshm.feature.analysis

import android.app.Application
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class AnalysisViewModelTest {

    @Test
    fun testAnalysisStateUpdates() {
        val application = mockk<Application>(relaxed = true)
        val tmpDir = System.getProperty("java.io.tmpdir") ?: "/tmp"
        every { application.filesDir } returns File(tmpDir)
        
        val vm = AnalysisViewModel(application)
        vm.updateResults(8.17, "X", -1.2, "GLOBAL_MODE")
        assertEquals(8.17, vm.uiState.value.fundamentalFrequencyHz, 0.001)
        assertEquals("X", vm.uiState.value.dominantAxis)
        assertEquals(-1.2, vm.uiState.value.baselineShiftPct, 0.001)
    }
}

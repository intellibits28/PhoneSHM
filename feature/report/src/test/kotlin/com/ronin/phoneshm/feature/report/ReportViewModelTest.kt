package com.ronin.phoneshm.feature.report

import org.junit.Assert.assertEquals
import org.junit.Test

class ReportViewModelTest {

    @Test
    fun testReportModeSwitching() {
        val vm = ReportViewModel()
        assertEquals(ReportMode.CITIZEN_SUMMARY, vm.uiState.value.mode)
        vm.toggleMode(ReportMode.ENGINEER_ADVANCED)
        assertEquals(ReportMode.ENGINEER_ADVANCED, vm.uiState.value.mode)
        vm.setReportSummary("Bridge A", "RESEARCH_GRADE", 4.5, false)
        assertEquals("Bridge A", vm.uiState.value.buildingName)
    }
}

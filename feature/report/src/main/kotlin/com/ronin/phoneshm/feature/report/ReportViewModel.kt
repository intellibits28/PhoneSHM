package com.ronin.phoneshm.feature.report

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ReportMode {
    CITIZEN_SUMMARY,
    ENGINEER_ADVANCED
}

data class ReportUiState(
    val mode: ReportMode = ReportMode.CITIZEN_SUMMARY,
    val buildingName: String = "",
    val qualityCategory: String = "RESEARCH_GRADE",
    val f0Hz: Double = 0.0,
    val anomalyDetected: Boolean = false
)

/**
 * ReportViewModel switches between Citizen Science summary and interactive Engineer Mode.
 */
class ReportViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(ReportUiState())
    val uiState: StateFlow<ReportUiState> = _uiState.asStateFlow()

    fun toggleMode(newMode: ReportMode) {
        _uiState.value = _uiState.value.copy(mode = newMode)
    }

    fun setReportSummary(building: String, quality: String, f0: Double, anomaly: Boolean) {
        _uiState.value = _uiState.value.copy(
            buildingName = building,
            qualityCategory = quality,
            f0Hz = f0,
            anomalyDetected = anomaly
        )
    }
}

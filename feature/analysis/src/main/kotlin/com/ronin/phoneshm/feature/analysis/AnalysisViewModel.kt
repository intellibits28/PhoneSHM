package com.ronin.phoneshm.feature.analysis

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AnalysisUiState(
    val isAnalyzing: Boolean = false,
    val fundamentalFrequencyHz: Double = 0.0,
    val dominantAxis: String = "MAGNITUDE",
    val qualityScorePct: Int = 100,
    val baselineShiftPct: Double = 0.0,
    val classificationLabel: String = "GLOBAL_MODE"
)

/**
 * AnalysisViewModel drives modal frequency display, Welch PSD rendering, and baseline comparison.
 */
class AnalysisViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(AnalysisUiState())
    val uiState: StateFlow<AnalysisUiState> = _uiState.asStateFlow()

    fun updateResults(f0: Double, axis: String, shift: Double, classification: String) {
        _uiState.value = _uiState.value.copy(
            fundamentalFrequencyHz = f0,
            dominantAxis = axis,
            baselineShiftPct = shift,
            classificationLabel = classification,
            isAnalyzing = false
        )
    }
}

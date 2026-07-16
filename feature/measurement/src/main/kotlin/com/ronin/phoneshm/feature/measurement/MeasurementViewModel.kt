package com.ronin.phoneshm.feature.measurement

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MeasurementUiState(
    val isRecording: Boolean = false,
    val elapsedSeconds: Int = 0,
    val currentSampleRateHz: Float = 0f,
    val clockJitterMs: Float = 0f,
    val currentQualityScorePct: Int = 100
)

/**
 * MeasurementViewModel controls live sensor recording HUD, circular buffer extraction, and state.
 */
class MeasurementViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(MeasurementUiState())
    val uiState: StateFlow<MeasurementUiState> = _uiState.asStateFlow()

    fun toggleRecording(start: Boolean) {
        _uiState.value = _uiState.value.copy(isRecording = start, elapsedSeconds = if (start) 0 else _uiState.value.elapsedSeconds)
    }

    fun updateMetrics(sampleRateHz: Float, jitterMs: Float, qualityScorePct: Int) {
        _uiState.value = _uiState.value.copy(
            currentSampleRateHz = sampleRateHz,
            clockJitterMs = jitterMs,
            currentQualityScorePct = qualityScorePct
        )
    }
}

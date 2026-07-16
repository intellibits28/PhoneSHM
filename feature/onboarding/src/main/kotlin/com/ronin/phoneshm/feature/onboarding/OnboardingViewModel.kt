package com.ronin.phoneshm.feature.onboarding

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class OnboardingState(
    val step: Int = 1,
    val isDeviceCalibrated: Boolean = false,
    val buildingName: String = "",
    val buildingType: String = "RESIDENTIAL_CONCRETE"
)

/**
 * OnboardingViewModel manages startup wizard state and building profile creation.
 */
class OnboardingViewModel : ViewModel() {
    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    fun updateBuildingName(name: String) {
        _state.value = _state.value.copy(buildingName = name)
    }

    fun setStep(newStep: Int) {
        _state.value = _state.value.copy(step = newStep)
    }
}

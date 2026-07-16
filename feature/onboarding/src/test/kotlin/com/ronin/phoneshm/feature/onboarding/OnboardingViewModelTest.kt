package com.ronin.phoneshm.feature.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test

class OnboardingViewModelTest {

    @Test
    fun testOnboardingStateUpdates() {
        val viewModel = OnboardingViewModel()
        assertEquals(1, viewModel.state.value.step)
        viewModel.updateBuildingName("Tower B")
        assertEquals("Tower B", viewModel.state.value.buildingName)
        viewModel.setStep(2)
        assertEquals(2, viewModel.state.value.step)
    }
}

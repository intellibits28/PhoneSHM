package com.ronin.phoneshm.feature.onboarding

import com.ronin.phoneshm.core.database.model.BuildingProfile
import com.ronin.phoneshm.core.database.model.MeasurementProfile
import com.ronin.phoneshm.core.database.repository.ProfileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingViewModelTest {

    private class FakeProfileRepository : ProfileRepository {
        var savedBuilding: BuildingProfile? = null
        var savedMeasurement: MeasurementProfile? = null

        override suspend fun saveBuildingProfile(profile: BuildingProfile) {
            savedBuilding = profile
        }

        override suspend fun getBuildingProfile(id: String): BuildingProfile? = savedBuilding
        override fun getAllBuildingProfiles(): Flow<List<BuildingProfile>> = flowOf(listOfNotNull(savedBuilding))

        override suspend fun saveMeasurementProfile(profile: MeasurementProfile) {
            savedMeasurement = profile
        }

        override suspend fun getMeasurementProfile(id: String): MeasurementProfile? = savedMeasurement
    }

    @Test
    fun testOnboardingWizardFlowAndPersistence() = runTest {
        val repo = FakeProfileRepository()
        val viewModel = OnboardingViewModel(profileRepository = repo)

        assertEquals(1, viewModel.state.value.step)
        viewModel.inspectDevice()
        viewModel.runCalibration()

        viewModel.setStep(2)
        viewModel.updateBuildingName("Tower 101")
        viewModel.updateBuildingType("COMMERCIAL_STEEL")
        viewModel.updateFloors("20")

        viewModel.setStep(3)
        viewModel.updateFloorLevel("10")
        viewModel.updateSurfaceType("CERAMIC_TILE")
        viewModel.updateLocationType("NEAR_COLUMN")
        viewModel.updatePlacement("FLAT_ON_FLOOR")

        viewModel.saveProfileAndFinish()

        assertTrue(viewModel.state.value.isCompleted)
        assertEquals(4, viewModel.state.value.step)
        assertEquals("Tower 101", repo.savedBuilding?.name)
        assertEquals("COMMERCIAL_STEEL", repo.savedBuilding?.type)
        assertEquals(20, repo.savedBuilding?.floors)
        assertEquals("CERAMIC_TILE", repo.savedMeasurement?.surfaceType)
    }
}

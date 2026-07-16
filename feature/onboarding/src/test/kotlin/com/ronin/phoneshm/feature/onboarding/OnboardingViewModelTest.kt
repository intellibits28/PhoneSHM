package com.ronin.phoneshm.feature.onboarding

import com.ronin.phoneshm.core.database.model.BuildingProfile
import com.ronin.phoneshm.core.database.model.MeasurementProfile
import com.ronin.phoneshm.core.database.repository.ProfileRepository
import com.ronin.phoneshm.core.location.LocationProfile
import com.ronin.phoneshm.core.location.LocationResolver
import com.ronin.phoneshm.core.location.PrivacyLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

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

    private class FakeLocationResolver : LocationResolver {
        override suspend fun resolveLocation(privacyLevel: PrivacyLevel): LocationProfile? {
            return LocationProfile(
                latitude = 16.8409,
                longitude = 96.1735,
                accuracyMeters = 5.0f,
                source = "GPS",
                buildingHash = "mock_hash_16_96",
                privacyLevel = privacyLevel
            )
        }

        override fun generateBuildingHash(lat: Double, lon: Double, buildingName: String): String {
            return "hash_${lat}_${lon}_$buildingName"
        }
    }

    @Test
    fun testOnboardingWizardFlowAndPersistence() = runTest {
        val repo = FakeProfileRepository()
        val resolver = FakeLocationResolver()
        val viewModel = OnboardingViewModel(
            profileRepository = repo,
            locationResolver = resolver
        )

        assertEquals(1, viewModel.state.value.step)
        viewModel.inspectDevice()
        viewModel.runCalibration()

        viewModel.setStep(2)
        viewModel.updateBuildingName("Tower 101")
        viewModel.updateBuildingType("COMMERCIAL_STEEL")
        viewModel.updateFloors("20")

        viewModel.setStep(3)
        viewModel.updatePrivacyLevel(PrivacyLevel.EXACT_LOCATION)
        viewModel.resolveCurrentLocation()
        assertEquals("hash_16.8409_96.1735_Tower 101", viewModel.state.value.resolvedBuildingHash)

        viewModel.setStep(4)
        viewModel.updateFloorLevel("10")
        viewModel.updateSurfaceType("CERAMIC_TILE")
        viewModel.updateLocationType("NEAR_COLUMN")
        viewModel.updatePlacement("FLAT_ON_FLOOR")

        viewModel.saveProfileAndFinish()

        assertTrue(viewModel.state.value.isCompleted)
        assertEquals(5, viewModel.state.value.step)
        assertEquals("Tower 101", repo.savedBuilding?.name)
        assertEquals("hash_16.8409_96.1735_Tower 101", repo.savedBuilding?.buildingHash)
        assertEquals("CERAMIC_TILE", repo.savedMeasurement?.surfaceType)
    }
}

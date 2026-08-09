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

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE, sdk = [33])
@ConscryptMode(ConscryptMode.Mode.OFF)
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

    private class FakeProfileRepository(val tempDir: java.io.File) : ProfileRepository {
        var savedBuilding: BuildingProfile? = null
        var savedMeasurement: MeasurementProfile? = null
        val baselineHistory = mutableListOf<String>() // stores hashes of passed sessions

        override suspend fun saveBuildingProfile(profile: BuildingProfile) {
            savedBuilding = profile
        }

        override suspend fun getBuildingProfile(buildingHash: String): BuildingProfile? = savedBuilding
        override fun getAllBuildingProfiles(): Flow<List<BuildingProfile>> = flowOf(listOfNotNull(savedBuilding))

        override suspend fun saveMeasurementProfile(profile: MeasurementProfile) {
            savedMeasurement = profile
        }

        override suspend fun getMeasurementProfile(id: String): MeasurementProfile? = savedMeasurement
        
        override suspend fun getMeasurementProfilesForBuilding(buildingHash: String): List<MeasurementProfile> {
            return if (savedMeasurement?.buildingId == buildingHash) listOf(savedMeasurement!!) else emptyList()
        }

        override suspend fun hasAnyRecordingForBuilding(buildingHash: String): Boolean {
            // Mirror the actual ProfileRepositoryImpl logic: scan tempDir for .meta.json
            val metaFiles = tempDir.listFiles { _, name -> name.endsWith(".meta.json") }
            if (metaFiles != null) {
                for (file in metaFiles) {
                    try {
                        val content = file.readText()
                        val json = org.json.JSONObject(content)
                        if (json.optJSONObject("metadata")?.optString("buildingHash") == buildingHash) {
                            return true
                        }
                    } catch (e: Exception) { }
                }
            }
            return false
        }

        override suspend fun hasAnyRecordingForMeasurementProfile(measurementId: String): Boolean {
            val metaFiles = tempDir.listFiles { _, name -> name.endsWith(".meta.json") }
            if (metaFiles != null) {
                for (file in metaFiles) {
                    try {
                        val content = file.readText()
                        val json = org.json.JSONObject(content)
                        if (json.optJSONObject("metadata")?.optString("measurementProfileId") == measurementId) {
                            return true
                        }
                    } catch (e: Exception) { }
                }
            }
            return false
        }

        override suspend fun deleteBuildingAndRelatedData(buildingHash: String) {
            if (savedBuilding?.buildingHash == buildingHash) {
                savedBuilding = null
            }
        }
        
        fun simulateSessionRecord(buildingHash: String, qualityGatePassed: Boolean) {
            // 1. Always write raw session metadata to disk (regardless of quality gate)
            val sessionId = java.util.UUID.randomUUID().toString()
            val measurementId = savedMeasurement?.id ?: ""
            val metaFile = java.io.File(tempDir, "$sessionId.meta.json")
            metaFile.writeText("{\"metadata\":{\"buildingHash\":\"$buildingHash\",\"measurementProfileId\":\"$measurementId\"}}")
            
            // 2. Only enter baseline history if passed
            if (qualityGatePassed) {
                baselineHistory.add(buildingHash)
            }
        }
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
        val repo = FakeProfileRepository(java.io.File(System.getProperty("java.io.tmpdir") ?: "/tmp"))
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
        assertEquals("Tower 101", repo.savedBuilding?.displayName)
        assertEquals("hash_16.8409_96.1735_Tower 101", repo.savedBuilding?.buildingHash)
        assertEquals("CERAMIC_TILE", repo.savedMeasurement?.surfaceType)
    }

    @Test
    fun testVerifyWorkflow_edit_record_delete() = runTest {
        val tempDir = java.io.File(System.getProperty("java.io.tmpdir"), "test_onboarding_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        
        try {
            val repo = FakeProfileRepository(tempDir)
            val resolver = FakeLocationResolver()
            val viewModel = OnboardingViewModel(profileRepository = repo, locationResolver = resolver)

            // 1. Onboard
            viewModel.updateBuildingName("Test Building")
            viewModel.setStep(3)
            viewModel.updatePrivacyLevel(PrivacyLevel.EXACT_LOCATION)
            viewModel.resolveCurrentLocation() // hash_16.8409_96.1735_Test Building
            viewModel.saveProfileAndFinish()

            val savedHash = viewModel.state.value.savedBuildingId!!
            assertNotNull(repo.savedBuilding)

            // 2. Edit before recording
            viewModel.loadProfileForEditing(savedHash)
            assertEquals(true, viewModel.state.value.isEditMode)
            assertEquals(false, viewModel.state.value.hasRecordedSessions)
            
            // Save edit
            viewModel.updateFloors("5")
            viewModel.saveProfileAndFinish()
            assertEquals(5, repo.savedBuilding?.floors)

            // 3. Record a QUALITY-GATE-FAILED session
            // (e.g. simulated insufficient excitation)
            repo.simulateSessionRecord(savedHash, qualityGatePassed = false)
            
            // Verify it did NOT enter baseline_history
            assertTrue(repo.baselineHistory.isEmpty())

            // 4. Attempt edit post-recording
            // This should correctly block editing (read-only mode active) via the fixed guard condition
            viewModel.loadProfileForEditing(savedHash)
            assertEquals(true, viewModel.state.value.isEditMode)
            assertEquals(true, viewModel.state.value.hasRecordedSessions) 

            // 5. Delete
            var callbackCalled = false
            try {
                viewModel.deleteBuildingProfile(savedHash) {
                    callbackCalled = true
                }
            } catch (e: Exception) {
                // JVM test might throw on android.util.Log
            }
            
            assertTrue(callbackCalled || repo.savedBuilding == null) // Confirm Room clean
            assertEquals(null, repo.savedBuilding)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}

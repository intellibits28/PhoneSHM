package com.ronin.phoneshm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ronin.phoneshm.core.database.PhoneShmDatabase
import com.ronin.phoneshm.core.database.dao.BaselineDao
import com.ronin.phoneshm.core.database.dao.ProfileDao
import com.ronin.phoneshm.core.database.model.BuildingProfile
import com.ronin.phoneshm.core.database.repository.ProfileRepositoryImpl
import com.ronin.phoneshm.core.baseline.DefaultBaselineManagerEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BaselineIntegrationTest {
    private lateinit var db: PhoneShmDatabase
    private lateinit var profileDao: ProfileDao
    private lateinit var baselineDao: BaselineDao
    private lateinit var profileRepo: ProfileRepositoryImpl
    private lateinit var baselineEngine: DefaultBaselineManagerEngine
    private lateinit var tempDir: File

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = androidx.room.Room.inMemoryDatabaseBuilder(context, PhoneShmDatabase::class.java).build()
        profileDao = db.profileDao()
        baselineDao = db.baselineDao()
        profileRepo = ProfileRepositoryImpl(profileDao, baselineDao, context)
        tempDir = File(context.cacheDir, "baseline_test_${UUID.randomUUID()}").apply { mkdirs() }
        baselineEngine = DefaultBaselineManagerEngine(baselineDao, tempDir)
    }

    @After
    fun teardown() {
        db.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun verifyMultiBuildingBaselines() = runBlocking {
        // Scenario 1: Onboard building A, record
        val hashA = UUID.randomUUID().toString()
        val buildingA = BuildingProfile(
            buildingHash = hashA,
            displayName = "Building A",
            buildingType = "Hospital",
            floors = 3,
            material = "Concrete"
        )
        profileRepo.saveBuildingProfile(buildingA)

        // Record a mock session for Building A
        baselineEngine.updateBaselineWithSession(
            buildingHash = hashA,
            currentF0Hz = 10.0,
            qualityScorePct = 80,
            measurementProfileId = "profileA"
        )
        
        var statA = baselineEngine.getOrCreateBaseline(hashA, "profileA")
        assertNotNull(statA)
        assertEquals(1, statA?.measurementCount)

        // Scenario 2: Create building B, ensure separate baselines
        val hashB = UUID.randomUUID().toString()
        val buildingB = BuildingProfile(
            buildingHash = hashB,
            displayName = "Building B",
            buildingType = "Residential",
            floors = 2,
            material = "Wood"
        )
        profileRepo.saveBuildingProfile(buildingB)

        // Record a mock session for Building B
        baselineEngine.updateBaselineWithSession(
            buildingHash = hashB,
            currentF0Hz = 15.0,
            qualityScorePct = 80,
            measurementProfileId = "profileB"
        )

        var statB = baselineEngine.getOrCreateBaseline(hashB, "profileB")
        assertNotNull(statB)
        assertEquals(1, statB?.measurementCount)

        // Confirm A and B are distinct
        assertNotEquals(statA?.meanF0Hz, statB?.meanF0Hz)

        // Scenario 3: Switch back to A, record again, confirm it resumes A's baseline
        baselineEngine.updateBaselineWithSession(
            buildingHash = hashA,
            currentF0Hz = 10.5,
            qualityScorePct = 80,
            measurementProfileId = "profileA"
        )
        
        statA = baselineEngine.getOrCreateBaseline(hashA, "profileA")
        assertNotNull(statA)
        assertEquals(2, statA?.measurementCount) // count incremented!
        
        // B should still have count=1
        statB = baselineEngine.getOrCreateBaseline(hashB, "profileB")
        assertEquals(1, statB?.measurementCount)
    }
}

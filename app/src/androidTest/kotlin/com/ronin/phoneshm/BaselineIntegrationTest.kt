package com.ronin.phoneshm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ronin.phoneshm.core.database.PhoneShmDatabase
import com.ronin.phoneshm.core.database.dao.BaselineDao
import com.ronin.phoneshm.core.database.dao.ProfileDao
import com.ronin.phoneshm.core.database.entity.BaselineStatEntity
import com.ronin.phoneshm.core.database.model.BuildingProfile
import com.ronin.phoneshm.core.database.repository.ProfileRepositoryImpl
import com.ronin.phoneshm.core.baseline.DefaultBaselineManagerEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BaselineIntegrationTest {
    private lateinit var db: PhoneShmDatabase
    private lateinit var profileDao: ProfileDao
    private lateinit var baselineDao: BaselineDao
    private lateinit var profileRepo: ProfileRepositoryImpl
    private lateinit var baselineEngine: DefaultBaselineManagerEngine

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = androidx.room.Room.inMemoryDatabaseBuilder(context, PhoneShmDatabase::class.java).build()
        profileDao = db.profileDao()
        baselineDao = db.baselineDao()
        profileRepo = ProfileRepositoryImpl(profileDao)
        baselineEngine = DefaultBaselineManagerEngine(context, baselineDao)
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun verifyMultiBuildingBaselines() = runBlocking {
        // Scenario 1: Onboard building A, record, force-close
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
        baselineEngine.incorporateNewObservation(hashA, "profileA", 3.0f, 10.0f, 0.8f, 2.0f)
        
        var statA = baselineDao.getBaselineForBuilding(hashA)
        assertNotNull(statA)
        assertEquals(1, statA?.n)

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
        baselineEngine.incorporateNewObservation(hashB, "profileB", 5.0f, 15.0f, 0.9f, 3.0f)

        var statB = baselineDao.getBaselineForBuilding(hashB)
        assertNotNull(statB)
        assertEquals(1, statB?.n)

        // Confirm A and B are distinct
        assertNotEquals(statA?.buildingId, statB?.buildingId)
        assertNotEquals(statA?.meanF0, statB?.meanF0)

        // Scenario 3: Switch back to A, record again, confirm it resumes A's baseline
        baselineEngine.incorporateNewObservation(hashA, "profileA", 3.2f, 10.5f, 0.85f, 2.1f)
        
        statA = baselineDao.getBaselineForBuilding(hashA)
        assertNotNull(statA)
        assertEquals(2, statA?.n) // n incremented!
        
        // B should still have n=1
        statB = baselineDao.getBaselineForBuilding(hashB)
        assertEquals(1, statB?.n)
    }
}

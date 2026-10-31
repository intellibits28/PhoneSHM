package com.ronin.phoneshm.core.baseline

import com.ronin.phoneshm.core.database.dao.BaselineDao
import com.ronin.phoneshm.core.database.entity.BaselineHistoryEntity
import com.ronin.phoneshm.core.database.entity.BaselineProfileEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.sqrt

class ThermalBaselineManagerEngineTest {

    private lateinit var baselineDir: File
    private lateinit var engine: DefaultBaselineManagerEngine
    private lateinit var fakeDao: FakeBaselineDao

    class FakeBaselineDao : BaselineDao {
        val profiles = mutableMapOf<String, BaselineProfileEntity>()
        val histories = mutableMapOf<String, MutableList<BaselineHistoryEntity>>()

        override suspend fun upsertProfile(profile: BaselineProfileEntity) {
            profiles[profile.buildingHash] = profile
        }

        override suspend fun insertHistory(history: BaselineHistoryEntity) {
            histories.getOrPut(history.buildingHash) { mutableListOf() }.add(history)
        }

        override suspend fun getProfile(buildingHash: String) = profiles[buildingHash]

        override suspend fun getHistory(buildingHash: String): List<BaselineHistoryEntity> {
            return histories[buildingHash]?.sortedBy { it.timestampMs } ?: emptyList()
        }

        override suspend fun trimHistoryTo20(buildingHash: String) {
            val list = histories[buildingHash] ?: return
            if (list.size > 20) {
                list.sortBy { it.timestampMs }
                histories[buildingHash] = list.takeLast(20).toMutableList()
            }
        }

        override suspend fun deleteProfile(buildingHash: String) {
            profiles.remove(buildingHash)
        }

        override suspend fun deleteHistory(buildingHash: String) {
            histories.remove(buildingHash)
        }

        override suspend fun deleteOrphanedLegacyProfiles() {
            profiles.keys.filter { !it.contains("_") }.toList().forEach { profiles.remove(it) }
        }

        override suspend fun deleteOrphanedLegacyHistory() {
            histories.keys.filter { !it.contains("_") }.toList().forEach { histories.remove(it) }
        }
    }

    @Before
    fun setup() {
        baselineDir = Files.createTempDirectory("thermal_baseline_test").toFile()
        fakeDao = FakeBaselineDao()
        engine = DefaultBaselineManagerEngine(fakeDao, baselineDir)
    }

    @Test
    fun testThermalDriftCompensationConvergesWithoutCatastrophicCancellation() = runTest {
        val hash = "bldg_thermal_stable"
        val trueRefF0 = 5.0
        val trueBeta = -0.012 // Hz / °C
        val refTemp = 25.0

        // Simulate 50 sessions cycling smoothly between 15°C and 40°C
        for (i in 0 until 50) {
            val tempC = (15.0 + (i % 10) * 2.5).toFloat()
            val f0 = trueRefF0 + trueBeta * (tempC.toDouble() - refTemp)
            engine.updateBaselineWithSession(
                buildingHash = hash,
                currentF0Hz = f0,
                qualityScorePct = 90,
                temperatureCelsius = tempC
            )
        }

        val profile = engine.getOrCreateBaseline(hash)
        assertNotNull(profile)
        assertEquals(50, profile!!.measurementCount)
        assertEquals(0, profile.consecutiveAnomalyCount)
        assertNotNull(profile.thermalSensitivityHzPerC)
        assertEquals(
            "Online bivariate Welford thermal slope must match true beta without cancellation",
            trueBeta,
            profile.thermalSensitivityHzPerC!!,
            1e-5
        )

        // Compare a healthy high-temperature session at 40°C (f0 = 4.82 Hz)
        val f0At40C = trueRefF0 + trueBeta * (40.0 - refTemp)
        val comparison = engine.compareWithBaseline(
            buildingHash = hash,
            currentF0Hz = f0At40C,
            confidence = 0.95,
            temperatureCelsius = 40.0f
        )
        assertFalse("Thermally compensated reading at 40°C must NOT trigger false anomaly", comparison.isAnomaly)
        assertEquals(0.0, comparison.percentageShift, 1e-3)
    }

    @Test
    fun testAnomalyFirstPreventsBaselineDrift() = runTest {
        val hash = "bldg_anomaly_first"
        val trueRefF0 = 6.0
        val trueBeta = -0.015
        val refTemp = 25.0

        // Establish 15 healthy sessions across temperatures
        for (i in 0 until 15) {
            val tempC = (20.0 + (i % 5) * 3.0).toFloat()
            val f0 = trueRefF0 + trueBeta * (tempC.toDouble() - refTemp)
            engine.updateBaselineWithSession(
                buildingHash = hash,
                currentF0Hz = f0,
                qualityScorePct = 88,
                temperatureCelsius = tempC
            )
        }

        val profileBefore = engine.getOrCreateBaseline(hash)!!
        val entityBefore = fakeDao.getProfile("${hash}_building_profile_active")!!
        assertEquals(15, profileBefore.measurementCount)
        assertEquals(0, profileBefore.consecutiveAnomalyCount)
        assertEquals(trueBeta, profileBefore.thermalSensitivityHzPerC!!, 1e-5)

        // Inject a damaged session (-7% structural stiffness loss) at 32°C
        val expectedHealthyAt32C = trueRefF0 + trueBeta * (32.0 - refTemp)
        val damagedF0 = expectedHealthyAt32C * 0.93 // -7% structural shift

        engine.updateBaselineWithSession(
            buildingHash = hash,
            currentF0Hz = damagedF0,
            qualityScorePct = 90,
            temperatureCelsius = 32.0f
        )

        val profileAfter = engine.getOrCreateBaseline(hash)!!
        val entityAfter = fakeDao.getProfile("${hash}_building_profile_active")!!

        // Anomaly count incremented and history recorded, but Welford & co-moment states strictly un-updated
        assertEquals(1, profileAfter.consecutiveAnomalyCount)
        assertEquals(15, profileAfter.measurementCount)
        assertEquals(profileBefore.meanF0Hz, profileAfter.meanF0Hz, 1e-12)
        assertEquals(profileBefore.stdF0Hz, profileAfter.stdF0Hz, 1e-12)
        assertEquals(profileBefore.thermalSensitivityHzPerC!!, profileAfter.thermalSensitivityHzPerC!!, 1e-12)
        assertEquals(entityBefore.coMomentTempF0!!, entityAfter.coMomentTempF0!!, 1e-12)
        assertEquals(16, profileAfter.recentHistory.size)
    }

    @Test
    fun testZeroVarianceTemperatureProtection() = runTest {
        val hash = "bldg_constant_temp"

        // 12 sessions all at the exact same temperature (25.0°C -> m2Temp == 0.0)
        for (i in 0 until 12) {
            engine.updateBaselineWithSession(
                buildingHash = hash,
                currentF0Hz = 4.50 + (i % 3) * 0.01,
                qualityScorePct = 85,
                temperatureCelsius = 25.0f
            )
        }

        val profile = engine.getOrCreateBaseline(hash)!!
        assertEquals(12, profile.measurementCount)
        assertEquals(12, profile.tempSampleCount)
        assertEquals(25.0, profile.meanTempC!!, 1e-9)
        // Must remain null rather than NaN or Infinity when temperature variance is zero
        assertNull("Thermal sensitivity must remain null when temperature is constant", profile.thermalSensitivityHzPerC)
    }

    @Test
    fun testMultiModeAndDirectionalAxisVarianceTracking() = runTest {
        val hash = "bldg_multimode"
        val xValues = listOf(3.10, 3.20, 3.30)
        val yValues = listOf(4.20, 4.40, 4.60)

        for (i in 0 until 3) {
            engine.updateBaselineWithSession(
                buildingHash = hash,
                currentF0Hz = xValues[i],
                qualityScorePct = 92,
                f0XHz = xValues[i],
                f0YHz = yValues[i],
                mode2Hz = 9.5 + i * 0.1,
                mode3Hz = 15.2 + i * 0.1,
                dampingEfdd = 0.02 + i * 0.005,
                dampingSsi = 0.018 + i * 0.002,
                consensusVariance = 0.0004
            )
        }

        val profile = engine.getOrCreateBaseline(hash)!!
        assertEquals(3.20, profile.meanF0XHz!!, 1e-9)
        assertEquals(sqrt(0.02 / 3.0), profile.stdF0XHz!!, 1e-9)
        assertEquals(4.40, profile.meanF0YHz!!, 1e-9)
        assertEquals(sqrt(0.08 / 3.0), profile.stdF0YHz!!, 1e-9)
        assertEquals(0.025, profile.meanDampingEfdd!!, 1e-9)
        assertEquals(0.020, profile.meanDampingSsi!!, 1e-9)

        val history = engine.getRecentHistory(hash)
        assertEquals(3, history.size)
        assertEquals(9.7, history.last().mode2Hz!!, 1e-9)
        assertEquals(15.4, history.last().mode3Hz!!, 1e-9)
        assertEquals(0.0004, history.last().consensusVariance!!, 1e-9)
    }
}

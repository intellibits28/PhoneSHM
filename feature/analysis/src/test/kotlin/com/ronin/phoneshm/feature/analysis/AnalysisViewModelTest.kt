package com.ronin.phoneshm.feature.analysis

import android.app.Application
import com.ronin.phoneshm.core.database.PhoneShmDatabase
import com.ronin.phoneshm.core.database.dao.BaselineDao
import com.ronin.phoneshm.core.database.entity.BaselineProfileEntity
import com.ronin.phoneshm.core.modal.DefaultModalAnalyzer
import com.ronin.phoneshm.core.modal.ModalAnalysisResult
import com.ronin.phoneshm.core.physics.FrequencyClassification
import com.ronin.phoneshm.core.physics.PlausibilityClassificationResult
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class AnalysisViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(PhoneShmDatabase)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun testAnalysisStateUpdates() {
        val application = mockk<Application>(relaxed = true)
        val tmpDir = System.getProperty("java.io.tmpdir") ?: "/tmp"
        every { application.filesDir } returns File(tmpDir)
        
        val vm = AnalysisViewModel(application)
        vm.updateResults(8.17, "X", -1.2, "GLOBAL_MODE")
        assertEquals(8.17, vm.uiState.value.fundamentalFrequencyHz, 0.001)
        assertEquals("X", vm.uiState.value.dominantAxis)
        assertEquals(-1.2, vm.uiState.value.baselineShiftPct, 0.001)
    }

    @Test
    fun testEndToEndE1LowQualityGating() = runTest(testDispatcher) {
        val application = mockk<Application>(relaxed = true)
        val tmpDir = System.getProperty("java.io.tmpdir") ?: "/tmp"
        every { application.filesDir } returns File(tmpDir)

        // Mock database and DAO
        val mockDb = mockk<PhoneShmDatabase>(relaxed = true)
        val mockDao = mockk<BaselineDao>(relaxed = true)
        every { PhoneShmDatabase.getDatabase(any()) } returns mockDb
        every { mockDb.baselineDao() } returns mockDao

        val testHash = "e2e_low_quality_bldg"
        val baselineProfile = BaselineProfileEntity(
            buildingHash = testHash,
            meanF0Hz = 25.195,
            stdF0Hz = 10.0184,
            measurementCount = 5,
            consecutiveAnomalyCount = 0,
            lastUpdatedAt = System.currentTimeMillis(),
            m2 = 400.0
        )
        coEvery { mockDao.getProfile(testHash) } returns baselineProfile
        coEvery { mockDao.getHistory(any()) } returns emptyList()
        coEvery { mockDao.upsertProfile(any()) } returns Unit
        coEvery { mockDao.insertHistory(any()) } returns Unit
        coEvery { mockDao.updateBaselineWithHistory(any(), any()) } returns Unit
        coEvery { mockDao.trimHistoryTo20(any()) } returns Unit

        // Mock ModalAnalyzer to return confidence = 0.20
        mockkConstructor(DefaultModalAnalyzer::class)
        val mockModalResult = ModalAnalysisResult(
            fundamentalFrequencyHz = 43.457,
            dominantAxis = "X",
            confidence = 0.20,
            persistence = 0.0,
            adaptiveToleranceHz = 0.0,
            classification = PlausibilityClassificationResult(
                classification = FrequencyClassification.GLOBAL_MODE,
                confidence = 1.0,
                explanation = "Physics Check"
            ),
            dominantPeaksTable = listOf(),
            prominenceRatio = 3.0,
            excitationSufficiency = com.ronin.phoneshm.core.modal.ExcitationSufficiency.SUFFICIENT
        )
        coEvery {
            anyConstructed<DefaultModalAnalyzer>().analyzeMultiAxisSpectrum(any(), any(), any())
        } returns mockModalResult

        val vm = AnalysisViewModel(application)
        vm.analyzeSessionFileOrDemo(null, buildingHash = testHash)
        
        // Advance coroutines (Main dispatcher)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.first { !it.isAnalyzing }
        assertNotNull("Baseline comparison result should not be null", state.baselineComparison)
        assertTrue(
            "comparisonSkippedLowQuality should thread through and be true",
            state.baselineComparison!!.comparisonSkippedLowQuality
        )
        assertEquals(
            "⚠️ Measurement quality too low for reliable comparison — retry recommended",
            state.baselineComparison!!.diagnosticSummary
        )
    }

    @Test
    fun testBaselineComparisonSkippedOnInsufficientExcitation() = runTest(testDispatcher) {
        val application = mockk<Application>(relaxed = true)
        val tmpDir = System.getProperty("java.io.tmpdir") ?: "/tmp"
        every { application.filesDir } returns File(tmpDir)

        // Mock database and DAO
        val mockDb = mockk<PhoneShmDatabase>(relaxed = true)
        val mockDao = mockk<BaselineDao>(relaxed = true)
        every { PhoneShmDatabase.getDatabase(any()) } returns mockDb
        every { mockDb.baselineDao() } returns mockDao

        // Database mock with existing profile so baselineEngine checks quality
        val mockProfile = com.ronin.phoneshm.core.database.entity.BaselineProfileEntity("B_INSUFF", 15.0, 0.5, 0.0, 10, 0, System.currentTimeMillis())
        coEvery { mockDao.getProfile(any()) } returns mockProfile
        coEvery { mockDao.getHistory(any()) } returns emptyList()
        coEvery { mockDao.upsertProfile(any()) } returns Unit
        coEvery { mockDao.insertHistory(any()) } returns Unit
        coEvery { mockDao.updateBaselineWithHistory(any(), any()) } returns Unit
        coEvery { mockDao.trimHistoryTo20(any()) } returns Unit

        // Mock ModalAnalyzer to return INSUFFICIENT
        mockkConstructor(DefaultModalAnalyzer::class)
        val mockModalResult = ModalAnalysisResult(
            fundamentalFrequencyHz = 15.0,
            dominantAxis = "Z",
            confidence = 0.90, // Even if high confidence
            persistence = 1.0,
            adaptiveToleranceHz = 0.5,
            classification = PlausibilityClassificationResult(
                classification = FrequencyClassification.GLOBAL_MODE,
                confidence = 1.0,
                explanation = "Physics Check"
            ),
            dominantPeaksTable = listOf(),
            prominenceRatio = 2.0,
            excitationSufficiency = com.ronin.phoneshm.core.modal.ExcitationSufficiency.INSUFFICIENT
        )
        coEvery {
            anyConstructed<DefaultModalAnalyzer>().analyzeMultiAxisSpectrum(any(), any(), any())
        } returns mockModalResult

        val vm = AnalysisViewModel(application)
        
        vm.analyzeSessionFileOrDemo(
            buildingHash = "B_INSUFF",
            buildingType = "High-Rise",
            floors = 10,
            filePath = null
        )

        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.first { !it.isAnalyzing }
        
        // Due to INSUFFICIENT excitation, confidence is coerced to 0.0 for baseline comparison,
        // which triggers the fail-safe low quality gate (since 0.0 < 0.50).
        assertNotNull("Baseline comparison result should not be null", state.baselineComparison)
        assertTrue("Comparison should be skipped/low-quality due to insufficient excitation coercing confidence to 0.0", state.baselineComparison!!.comparisonSkippedLowQuality)
    }
}

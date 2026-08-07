package com.ronin.phoneshm.feature.analysis

import android.app.Application
import com.ronin.phoneshm.core.database.PhoneShmDatabase
import com.ronin.phoneshm.core.database.dao.BaselineDao
import com.ronin.phoneshm.core.quality.QualityScoreEngine
import com.ronin.phoneshm.core.quality.MeasurementQualityReport
import com.ronin.phoneshm.core.storage.RawSampleSessionData
import com.ronin.phoneshm.core.storage.RawSampleStorageEngine
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

@OptIn(ExperimentalCoroutinesApi::class)
class AnalysisViewModelQualityTest {

    @Before
    fun setup() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        mockkObject(PhoneShmDatabase)
        
        mockkStatic(android.util.Log::class)
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun waitForViewModel(vm: AnalysisViewModel) = runBlocking {
        var attempts = 0
        while (vm.uiState.value.isAnalyzing && attempts < 100) {
            delay(50)
            attempts++
        }
    }

    private fun createDummyMetaFile(file: File) {
        val json = """
        {
            "metadata": {
                "version": 1,
                "sessionId": "test-session",
                "measurementProfileId": "test-profile-1",
                "deviceCapabilityReportId": "test-report",
                "targetDurationSeconds": 60,
                "targetSampleRateHz": 100,
                "actualAverageSampleRateHz": 100.0,
                "sampleJitterStdMs": 1.0,
                "clockDriftPpm": 0.0,
                "rawStorageFileUri": "file://dummy.dat",
                "timestampMs": 123456789,
                "buildingType": "RESIDENTIAL_CONCRETE",
                "buildingName": "Test Bldg",
                "buildingHash": "hash1",
                "floors": 3,
                "constructionYear": 2005,
                "material": "Reinforced Concrete",
                "floorLevel": "Ground",
                "surfaceType": "Tile",
                "locationType": "Center",
                "placement": "Floor"
            },
            "deviceReport": {
                "deviceModel": "TestDevice",
                "sensorVendor": "TestVendor",
                "maxSupportedSampleRateHz": 200,
                "estimatedNoiseFloorMg": 0.5,
                "accelerometerBias": [0.0, 0.0, 0.0],
                "qualityTier": "GOOD"
            }
        }
        """.trimIndent()
        file.writeText(json)
    }

    @Test
    fun testQualityScoreIntegration_HighQuality_Applied() {
        val application = mockk<Application>(relaxed = true)
        val tmpDir = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "quality_test_${System.currentTimeMillis()}")
        tmpDir.mkdirs()
        every { application.filesDir } returns tmpDir
        
        val mockDb = mockk<PhoneShmDatabase>(relaxed = true)
        every { PhoneShmDatabase.getDatabase(any()) } returns mockDb
        val mockDao = mockk<BaselineDao>(relaxed = true)
        every { mockDb.baselineDao() } returns mockDao
        
        var updateBaselineWithHistoryCalled = false
        coEvery { mockDao.getProfile(any()) } returns null
        coEvery { mockDao.updateBaselineWithHistory(any(), any()) } answers {
            updateBaselineWithHistoryCalled = true
            Unit
        }

        val vm = AnalysisViewModel(application)
        
        val mockQualityScoreEngine = mockk<QualityScoreEngine>()
        val mockReport = mockk<MeasurementQualityReport>(relaxed = true)
        every { mockReport.totalScorePct } returns 95
        every { mockQualityScoreEngine.calculateQualityScore(any(), any(), any(), any()) } returns mockReport
        
        val field = AnalysisViewModel::class.java.getDeclaredField("qualityScoreEngine")
        field.isAccessible = true
        field.set(vm, mockQualityScoreEngine)

        val mockStorageEngine = mockk<RawSampleStorageEngine>()
        coEvery { mockStorageEngine.readSamplesFromFile(any()) } returns RawSampleSessionData(LongArray(0), FloatArray(0), FloatArray(0), FloatArray(0))
        val storageField = AnalysisViewModel::class.java.getDeclaredField("storageEngine")
        storageField.isAccessible = true
        storageField.set(vm, mockStorageEngine)

        val binFile = File(tmpDir, "dummy_high_quality.dat")
        val metaFile = File(tmpDir, "dummy_high_quality.meta.json")
        createDummyMetaFile(metaFile)
        
        val bbHigh = ByteBuffer.allocate(12)
        bbHigh.putInt(0)
        bbHigh.putLong(0L)
        binFile.writeBytes(bbHigh.array())
        
        vm.analyzeSessionFileOrDemo(binFile.absolutePath, "RESIDENTIAL_CONCRETE", 3, "hash1")
        waitForViewModel(vm)

        val state = vm.uiState.value
        assertTrue("High quality should have score >= 50, got ${state.qualityScorePct}. isAnalyzing=${state.isAnalyzing}", state.qualityScorePct >= 50)
        assertTrue("DAO method should be called", updateBaselineWithHistoryCalled)
    }

    @Test
    fun testQualityScoreIntegration_LowQuality_Gated() {
        val application = mockk<Application>(relaxed = true)
        val tmpDir = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "quality_test_${System.currentTimeMillis()}")
        tmpDir.mkdirs()
        every { application.filesDir } returns tmpDir
        
        val mockDb = mockk<PhoneShmDatabase>(relaxed = true)
        every { PhoneShmDatabase.getDatabase(any()) } returns mockDb
        val mockDao = mockk<BaselineDao>(relaxed = true)
        every { mockDb.baselineDao() } returns mockDao
        
        var updateBaselineWithHistoryCalled = false
        coEvery { mockDao.updateBaselineWithHistory(any(), any()) } answers {
            updateBaselineWithHistoryCalled = true
            Unit
        }

        val vm = AnalysisViewModel(application)
        
        val mockQualityScoreEngine = mockk<QualityScoreEngine>()
        val mockReport = mockk<MeasurementQualityReport>(relaxed = true)
        every { mockReport.totalScorePct } returns 45
        every { mockQualityScoreEngine.calculateQualityScore(any(), any(), any(), any()) } returns mockReport
        
        val field = AnalysisViewModel::class.java.getDeclaredField("qualityScoreEngine")
        field.isAccessible = true
        field.set(vm, mockQualityScoreEngine)

        val mockStorageEngine = mockk<RawSampleStorageEngine>()
        coEvery { mockStorageEngine.readSamplesFromFile(any()) } returns RawSampleSessionData(LongArray(0), FloatArray(0), FloatArray(0), FloatArray(0))
        val storageField = AnalysisViewModel::class.java.getDeclaredField("storageEngine")
        storageField.isAccessible = true
        storageField.set(vm, mockStorageEngine)

        val binFile = File(tmpDir, "dummy_low_quality.dat")
        val metaFile = File(tmpDir, "dummy_low_quality.meta.json")
        createDummyMetaFile(metaFile)
        
        val bbLow = ByteBuffer.allocate(12)
        bbLow.putInt(0)
        bbLow.putLong(0L)
        binFile.writeBytes(bbLow.array())

        vm.analyzeSessionFileOrDemo(binFile.absolutePath, "RESIDENTIAL_CONCRETE", 3, "hash2")
        waitForViewModel(vm)

        val state = vm.uiState.value
        assertTrue("Low quality should have score < 50, got ${state.qualityScorePct}. isAnalyzing=${state.isAnalyzing}", state.qualityScorePct < 50)
        assertTrue("DAO method should NOT be called", !updateBaselineWithHistoryCalled)
    }
}

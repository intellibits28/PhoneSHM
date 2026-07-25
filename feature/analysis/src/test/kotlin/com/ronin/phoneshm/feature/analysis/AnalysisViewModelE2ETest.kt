package com.ronin.phoneshm.feature.analysis

import android.app.Application
import com.ronin.phoneshm.core.database.PhoneShmDatabase
import com.ronin.phoneshm.core.database.dao.BaselineDao
import com.ronin.phoneshm.core.storage.DefaultRawSampleStorageEngine
import com.ronin.phoneshm.core.storage.StorageFormat
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.math.sin

@OptIn(ExperimentalCoroutinesApi::class)
class AnalysisViewModelE2ETest {

    private lateinit var tempDir: File

    @Before
    fun setup() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        tempDir = File(System.getProperty("java.io.tmpdir"), "e2e_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        
        mockkObject(PhoneShmDatabase)
        mockkStatic(android.util.Log::class)
        every { android.util.Log.e(any(), any()) } answers {
            println("Log.e: ${arg<String>(0)} - ${arg<String>(1)}")
            0
        }
        every { android.util.Log.e(any(), any(), any()) } answers {
            println("Log.e: ${arg<String>(0)} - ${arg<String>(1)}")
            arg<Throwable>(2).printStackTrace()
            0
        }
        every { android.util.Log.w(any(), any<String>()) } returns 0
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        unmockkAll()
        tempDir.deleteRecursively()
    }

    private fun waitForViewModel(vm: AnalysisViewModel) = runBlocking {
        var attempts = 0
        while (vm.uiState.value.isAnalyzing && attempts < 50) {
            delay(100)
            attempts++
        }
    }

    @Test
    fun testRealJsonSidecarReadAndQualityScoreApplied() = runBlocking {
        // We do NOT mock QualityScoreEngine here. We use the real one built into the ViewModel.
        // We only mock the Application context
        val mockApp = mockk<Application>(relaxed = true)
        every { mockApp.filesDir } returns tempDir
        every { mockApp.applicationContext } returns mockApp

        // 1. Create a real binary file using storage engine so it is valid
        val storageEngine = DefaultRawSampleStorageEngine(tempDir)
        val sessionId = "e2e_test_session_1"
        val binFile = storageEngine.createSessionFile(sessionId, StorageFormat.BINARY_LITTLE_ENDIAN)
        
        // Write some real-looking data
        val sampleCount = 4096
        val timestamps = LongArray(sampleCount) { i -> i * 10_000_000L }
        val x = FloatArray(sampleCount) { i -> (0.01 * sin(i * 0.01)).toFloat() }
        val y = FloatArray(sampleCount) { i -> (0.01 * sin(i * 0.01)).toFloat() }
        val z = FloatArray(sampleCount) { i -> (9.81 + 0.01 * sin(i * 0.01)).toFloat() }
        storageEngine.appendSamplesBatch(binFile, timestamps, x, y, z)
        storageEngine.finalizeSessionFile(binFile)

        // 2. Write the JSON sidecar file using the shared codec (Fixes Item #2)
        val metaFile = File(binFile.parentFile, "$sessionId.meta.json")
        val meta = com.ronin.phoneshm.core.sensor.MeasurementSessionMetadata(
            sessionId = sessionId,
            measurementProfileId = "profile_test",
            deviceCapabilityReportId = "RESEARCH_GRADE",
            targetDurationSeconds = 41,
            targetSampleRateHz = 100,
            actualAverageSampleRateHz = 100.0f,
            sampleJitterStdMs = 1.5f,
            clockDriftPpm = 0.0f,
            rawStorageFileUri = binFile.absolutePath
        )
        val dev = com.ronin.phoneshm.core.device.DeviceCapabilityReport(
            deviceModel = "E2E Test Device",
            sensorVendor = "E2E Vendor",
            maxSupportedSampleRateHz = 200,
            estimatedNoiseFloorMg = 2.0f,
            accelerometerBias = FloatArray(3) { 0f },
            qualityTier = com.ronin.phoneshm.core.device.SensorQualityTier.RESEARCH_GRADE
        )
        metaFile.writeText(com.ronin.phoneshm.core.sensor.SessionMetadataJsonCodec.encode(meta, dev))

        // 3. Mock the database components
        val mockDb = mockk<PhoneShmDatabase>(relaxed = true)
        val mockDao = mockk<BaselineDao>(relaxed = true)
        every { PhoneShmDatabase.getDatabase(any()) } returns mockDb
        every { mockDb.baselineDao() } returns mockDao
        
        val vm = AnalysisViewModel(mockApp)

        // 4. Run Analysis
        vm.analyzeSessionFileOrDemo(binFile.absolutePath, "RESIDENTIAL_CONCRETE", 3, "hash1")
        waitForViewModel(vm)

        // 5. Assertions
        val state = vm.uiState.value
        // It shouldn't be the fallback value 49
        assertTrue("Parsed quality score should not be 49 fallback, got ${state.qualityScorePct}", state.qualityScorePct != 49)
        assertNotNull("Quality Report should have been parsed and generated", state.qualityReport)
        
        // Quality should be decent for a TIER_1 device with 1.5ms jitter
        assertTrue("Quality score should be > 50 for good metadata, got ${state.qualityScorePct}", state.qualityScorePct > 50)
    }
}

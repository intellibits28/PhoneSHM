package com.ronin.phoneshm.core.storage

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RawSampleStorageEngineTest {

    private class FakeRawSampleStorageEngine : RawSampleStorageEngine {
        override suspend fun createSessionFile(sessionId: String, format: StorageFormat): File {
            return File(System.getProperty("java.io.tmpdir"), "$sessionId.bin")
        }

        override suspend fun appendSamplesBatch(file: File, timestampsNs: LongArray, x: FloatArray, y: FloatArray, z: FloatArray) {
            // Placeholder write for unit test verification
        }

        override suspend fun finalizeSessionFile(file: File): Pair<Long, Int> {
            return Pair(1024L, 100)
        }
    }

    @Test
    fun testRawStorageLifecycle() = runTest {
        val engine = FakeRawSampleStorageEngine()
        val file = engine.createSessionFile("session_test")
        engine.appendSamplesBatch(file, longArrayOf(1L), floatArrayOf(0f), floatArrayOf(0f), floatArrayOf(9.8f))
        val (bytes, samples) = engine.finalizeSessionFile(file)
        assertEquals(1024L, bytes)
        assertEquals(100, samples)
    }
}

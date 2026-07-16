package com.ronin.phoneshm.core.storage

import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RawSampleStorageEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageEngine: DefaultRawSampleStorageEngine
    private lateinit var testDir: File

    @Before
    fun setUp() {
        testDir = tempFolder.newFolder("raw_samples")
        storageEngine = DefaultRawSampleStorageEngine(testDir)
    }

    @Test
    fun testBinaryLittleEndianStorageLifecycle() = runTest {
        val sessionId = "session_binary_test"
        val file = storageEngine.createSessionFile(sessionId, StorageFormat.BINARY_LITTLE_ENDIAN)
        
        val timestamps = longArrayOf(1000000000L, 2000000000L)
        val x = floatArrayOf(0.1f, 0.2f)
        val y = floatArrayOf(-0.1f, -0.2f)
        val z = floatArrayOf(9.8f, 9.7f)

        storageEngine.appendSamplesBatch(file, timestamps, x, y, z)
        
        val (bytes, sampleCount) = storageEngine.finalizeSessionFile(file)
        
        assertEquals(40L, bytes) // 2 samples * 20 bytes = 40 bytes
        assertEquals(2, sampleCount)

        // Read and verify little-endian values
        val readBytes = file.readBytes()
        val buffer = ByteBuffer.wrap(readBytes).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(1000000000L, buffer.getLong())
        assertEquals(0.1f, buffer.getFloat(), 0.001f)
        assertEquals(-0.1f, buffer.getFloat(), 0.001f)
        assertEquals(9.8f, buffer.getFloat(), 0.001f)

        assertEquals(2000000000L, buffer.getLong())
        assertEquals(0.2f, buffer.getFloat(), 0.001f)
        assertEquals(-0.2f, buffer.getFloat(), 0.001f)
        assertEquals(9.7f, buffer.getFloat(), 0.001f)
    }

    @Test
    fun testCsvGzipStorageLifecycle() = runTest {
        val sessionId = "session_csv_test"
        val file = storageEngine.createSessionFile(sessionId, StorageFormat.CSV_GZIP)
        
        val timestamps = longArrayOf(1000000000L, 2000000000L)
        val x = floatArrayOf(0.1f, 0.2f)
        val y = floatArrayOf(-0.1f, -0.2f)
        val z = floatArrayOf(9.8f, 9.7f)

        storageEngine.appendSamplesBatch(file, timestamps, x, y, z)
        
        val (bytes, sampleCount) = storageEngine.finalizeSessionFile(file)
        
        assertEquals(2, sampleCount)
        assertTrue(bytes > 0)

        // The uncompressed file should have been deleted
        assertTrue(!file.exists())

        // The gzip file should exist
        val gzipFile = File(testDir, "$sessionId.csv.gz")
        assertTrue(gzipFile.exists())

        // Read and verify Gzip content
        val lines = GZIPInputStream(FileInputStream(gzipFile)).bufferedReader().readLines()
        assertEquals(2, lines.size)
        assertEquals("1000000000,0.1,-0.1,9.8", lines[0])
        assertEquals("2000000000,0.2,-0.2,9.7", lines[1])
    }
}

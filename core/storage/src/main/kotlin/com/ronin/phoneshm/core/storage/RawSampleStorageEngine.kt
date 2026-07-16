package com.ronin.phoneshm.core.storage

import java.io.File
import kotlinx.coroutines.flow.Flow

/**
 * StorageFormat designates how raw 100Hz vibration sample streams are serialized.
 */
enum class StorageFormat {
    BINARY_LITTLE_ENDIAN,
    CSV_GZIP
}

/**
 * RawSampleStorageEngine streams raw high-frequency accelerometer streams (100Hz+)
 * directly to the local filesystem (binary/CSV) without hitting Room DB.
 */
interface RawSampleStorageEngine {
    /**
     * Allocates file destination and initializes write stream for a new measurement session.
     */
    suspend fun createSessionFile(sessionId: String, format: StorageFormat = StorageFormat.BINARY_LITTLE_ENDIAN): File

    /**
     * Appends batch of raw sensor samples (timestampNs, x, y, z) to open session file.
     */
    suspend fun appendSamplesBatch(file: File, timestampsNs: LongArray, x: FloatArray, y: FloatArray, z: FloatArray)

    /**
     * Finalizes file stream and returns total bytes written and sample count.
     */
    suspend fun finalizeSessionFile(file: File): Pair<Long, Int>

    /**
     * Reads back all serialized samples from a finalized binary/CSV/GZIP session file.
     */
    suspend fun readSamplesFromFile(file: File): RawSampleSessionData
}

data class RawSampleSessionData(
    val timestampsNs: LongArray,
    val x: FloatArray,
    val y: FloatArray,
    val z: FloatArray
) {
    val sampleCount: Int get() = timestampsNs.size

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RawSampleSessionData) return false
        return timestampsNs.contentEquals(other.timestampsNs) &&
                x.contentEquals(other.x) &&
                y.contentEquals(other.y) &&
                z.contentEquals(other.z)
    }

    override fun hashCode(): Int {
        var result = timestampsNs.contentHashCode()
        result = 31 * result + x.contentHashCode()
        result = 31 * result + y.contentHashCode()
        result = 31 * result + z.contentHashCode()
        return result
    }
}


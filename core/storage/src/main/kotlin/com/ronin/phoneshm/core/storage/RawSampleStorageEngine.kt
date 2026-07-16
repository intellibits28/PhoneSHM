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
}

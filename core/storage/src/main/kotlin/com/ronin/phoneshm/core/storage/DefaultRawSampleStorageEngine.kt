package com.ronin.phoneshm.core.storage

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DefaultRawSampleStorageEngine(
    private val baseDir: File
) : RawSampleStorageEngine {

    override suspend fun createSessionFile(sessionId: String, format: StorageFormat): File = withContext(Dispatchers.IO) {
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
        val extension = when (format) {
            StorageFormat.BINARY_LITTLE_ENDIAN -> "bin"
            StorageFormat.CSV_GZIP -> "csv"
        }
        val file = File(baseDir, "$sessionId.$extension")
        if (file.exists()) {
            file.delete()
        }
        file.createNewFile()
        file
    }

    override suspend fun appendSamplesBatch(
        file: File,
        timestampsNs: LongArray,
        x: FloatArray,
        y: FloatArray,
        z: FloatArray
    ) = withContext(Dispatchers.IO) {
        if (timestampsNs.isEmpty()) return@withContext

        if (file.name.endsWith(".bin")) {
            val buffer = ByteBuffer.allocate(timestampsNs.size * 20).order(ByteOrder.LITTLE_ENDIAN)
            for (i in timestampsNs.indices) {
                buffer.putLong(timestampsNs[i])
                buffer.putFloat(x[i])
                buffer.putFloat(y[i])
                buffer.putFloat(z[i])
            }
            FileOutputStream(file, true).use { fos ->
                fos.write(buffer.array())
            }
        } else if (file.name.endsWith(".csv")) {
            FileWriter(file, true).use { writer ->
                for (i in timestampsNs.indices) {
                    writer.write("${timestampsNs[i]},${x[i]},${y[i]},${z[i]}\n")
                }
            }
        }
    }

    override suspend fun finalizeSessionFile(file: File): Pair<Long, Int> = withContext(Dispatchers.IO) {
        if (!file.exists()) {
            return@withContext Pair(0L, 0)
        }

        if (file.name.endsWith(".csv")) {
            val gzipFile = File(file.parentFile, "${file.name}.gz")
            if (gzipFile.exists()) {
                gzipFile.delete()
            }
            var sampleCount = 0
            FileInputStream(file).bufferedReader().use { reader ->
                FileOutputStream(gzipFile).let { fos ->
                    GZIPOutputStream(fos).bufferedWriter().use { writer ->
                        var line = reader.readLine()
                        while (line != null) {
                            if (line.isNotEmpty()) {
                                writer.write(line)
                                writer.newLine()
                                sampleCount++
                            }
                            line = reader.readLine()
                        }
                    }
                }
            }
            file.delete() // Clean up uncompressed CSV
            Pair(gzipFile.length(), sampleCount)
        } else {
            // Binary file
            val sampleCount = (file.length() / 20).toInt()
            Pair(file.length(), sampleCount)
        }
    }

    override suspend fun readSamplesFromFile(file: File): RawSampleSessionData = withContext(Dispatchers.IO) {
        if (!file.exists()) {
            return@withContext RawSampleSessionData(LongArray(0), FloatArray(0), FloatArray(0), FloatArray(0))
        }

        if (file.name.endsWith(".bin")) {
            val bytes = file.readBytes()
            val sampleCount = bytes.size / 20
            val timestamps = LongArray(sampleCount)
            val x = FloatArray(sampleCount)
            val y = FloatArray(sampleCount)
            val z = FloatArray(sampleCount)
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until sampleCount) {
                timestamps[i] = buffer.long
                x[i] = buffer.float
                y[i] = buffer.float
                z[i] = buffer.float
            }
            RawSampleSessionData(timestamps, x, y, z)
        } else {
            // CSV or CSV.GZ
            val timestampsList = mutableListOf<Long>()
            val xList = mutableListOf<Float>()
            val yList = mutableListOf<Float>()
            val zList = mutableListOf<Float>()

            val inputStream = if (file.name.endsWith(".gz")) {
                java.util.zip.GZIPInputStream(FileInputStream(file))
            } else {
                FileInputStream(file)
            }

            inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.isNotBlank()) {
                        val parts = line.split(',')
                        if (parts.size >= 4) {
                            timestampsList.add(parts[0].toLongOrNull() ?: 0L)
                            xList.add(parts[1].toFloatOrNull() ?: 0f)
                            yList.add(parts[2].toFloatOrNull() ?: 0f)
                            zList.add(parts[3].toFloatOrNull() ?: 0f)
                        }
                    }
                }
            }
            RawSampleSessionData(
                timestampsList.toLongArray(),
                xList.toFloatArray(),
                yList.toFloatArray(),
                zList.toFloatArray()
            )
        }
    }
}

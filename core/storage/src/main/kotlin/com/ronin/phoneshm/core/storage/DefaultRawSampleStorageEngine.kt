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
        val file = File(baseDir, "$sessionId.$extension.tmp")
        if (file.exists()) {
            file.delete()
        }
        file.createNewFile()
        if (format == StorageFormat.BINARY_LITTLE_ENDIAN) {
            FileOutputStream(file).use { it.write("SHM1".toByteArray(Charsets.US_ASCII)) }
        }
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

        if (file.name.endsWith(".bin") || file.name.endsWith(".bin.tmp")) {
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
        } else if (file.name.endsWith(".csv") || file.name.endsWith(".csv.tmp")) {
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

        if (file.name.endsWith(".csv") || file.name.endsWith(".csv.tmp")) {
            val finalName = file.name.removeSuffix(".tmp")
            val gzipFile = File(file.parentFile, "${finalName}.gz")
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
            var offset = 0L
            val raf = java.io.RandomAccessFile(file, "r")
            if (raf.length() >= 4) {
                val header = ByteArray(4)
                raf.readFully(header)
                if (String(header, Charsets.US_ASCII) == "SHM1") {
                    offset = 4L
                }
            }
            raf.close()
            
            val sampleCount = ((file.length() - offset) / 20).toInt()
            val finalFile = File(file.parentFile, file.name.removeSuffix(".tmp"))
            file.renameTo(finalFile)
            Pair(finalFile.length(), sampleCount)
        }
    }

    override suspend fun readSamplesFromFile(file: File): RawSampleSessionData = withContext(Dispatchers.IO) {
        if (!file.exists()) {
            return@withContext RawSampleSessionData(LongArray(0), FloatArray(0), FloatArray(0), FloatArray(0))
        }

        if (file.name.endsWith(".bin")) {
            val bytes = file.readBytes()
            var offset = 0
            if (bytes.size >= 4 && String(bytes.copyOfRange(0, 4), Charsets.US_ASCII) == "SHM1") {
                offset = 4
            }
            val sampleCount = (bytes.size - offset) / 20
            val timestamps = LongArray(sampleCount)
            val x = FloatArray(sampleCount)
            val y = FloatArray(sampleCount)
            val z = FloatArray(sampleCount)
            val buffer = ByteBuffer.wrap(bytes, offset, bytes.size - offset).order(ByteOrder.LITTLE_ENDIAN)
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

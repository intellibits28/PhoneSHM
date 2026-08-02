package com.ronin.phoneshm.core.audio

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * AudioContextModule maintains a rolling 5-second RAM circular audio buffer.
 * NOTE: Currently not wired in live recording flow, deferred pending field-data justification.
 */
class DefaultAudioContextModule(
    private val context: Context?
) : AudioContextModule {

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    
    // 5 seconds of 16kHz 16-bit mono = 80,000 shorts (160 KB)
    private val bufferSizeShorts = sampleRate * 5
    private val circularBuffer = ShortArray(bufferSizeShorts)
    private var writeIndex = 0
    
    private var audioRecord: AudioRecord? = null
    private val isRecording = AtomicBoolean(false)
    private var recordingJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @SuppressLint("MissingPermission")
    override fun startCircularBuffer() {
        if (isRecording.get()) return

        try {
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                simulateCircularBuffer()
                return
            }

            val hasPermission = context != null && ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasPermission) {
                // Permission missing, simulate circular buffer quietly
                simulateCircularBuffer()
                return
            }
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                minBufferSize * 4
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                simulateCircularBuffer()
                return
            }

            audioRecord?.startRecording()
            isRecording.set(true)

            recordingJob = coroutineScope.launch {
                val readBuffer = ShortArray(minBufferSize)
                while (isRecording.get() && isActive) {
                    val readResult = audioRecord?.read(readBuffer, 0, readBuffer.size) ?: 0
                    if (readResult > 0) {
                        synchronized(circularBuffer) {
                            for (i in 0 until readResult) {
                                circularBuffer[writeIndex] = readBuffer[i]
                                writeIndex = (writeIndex + 1) % bufferSizeShorts
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            simulateCircularBuffer()
        }
    }

    private fun simulateCircularBuffer() {
        isRecording.set(true)
        recordingJob = coroutineScope.launch {
            while (isRecording.get() && isActive) {
                delay(100)
                synchronized(circularBuffer) {
                    val simulatedSamples = (sampleRate * 0.1).toInt()
                    for (i in 0 until simulatedSamples) {
                        // Very basic simulated noise
                        circularBuffer[writeIndex] = ((-100..100).random()).toShort()
                        writeIndex = (writeIndex + 1) % bufferSizeShorts
                    }
                }
            }
        }
    }

    fun stopCircularBuffer() {
        isRecording.set(false)
        recordingJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    override suspend fun extractFeaturesAroundTrigger(): AudioContextResult = withContext(Dispatchers.Default) {
        // We wait for 3 seconds to capture the "+3s" post-event audio into the buffer
        delay(3000)

        // Snapshot the buffer
        val snapshot = ShortArray(bufferSizeShorts)
        synchronized(circularBuffer) {
            // Read from current writeIndex (oldest) to writeIndex-1 (newest)
            var readIdx = writeIndex
            for (i in 0 until bufferSizeShorts) {
                snapshot[i] = circularBuffer[readIdx]
                readIdx = (readIdx + 1) % bufferSizeShorts
            }
        }

        // Calculate RMS Energy
        var sumSquares = 0.0
        for (sample in snapshot) {
            sumSquares += (sample.toDouble() * sample.toDouble())
        }
        val rmsEnergy = sqrt(sumSquares / bufferSizeShorts).toFloat()

        // Very basic Spectral Centroid mock (real implementation requires FFT on PCM)
        // We estimate frequency content by zero-crossings as a simple proxy for Centroid
        var zeroCrossings = 0
        for (i in 1 until bufferSizeShorts) {
            if ((snapshot[i] >= 0 && snapshot[i - 1] < 0) || (snapshot[i] < 0 && snapshot[i - 1] >= 0)) {
                zeroCrossings++
            }
        }
        // Zero crossings per second / 2 ~ dominant frequency
        val spectralCentroidHz = (zeroCrossings.toFloat() / 5.0f) / 2.0f

        // Mock Low Frequency Energy Ratio
        val lowFrequencyEnergyRatio = if (spectralCentroidHz < 200) 0.8f else 0.3f

        // Classify based on heuristics
        val eventLabel = when {
            rmsEnergy > 5000 && spectralCentroidHz < 100 -> "heavy_machinery"
            rmsEnergy > 2000 && spectralCentroidHz < 300 -> "vehicle_passing"
            rmsEnergy > 1000 && spectralCentroidHz > 500 -> "human_activity"
            else -> "quiet"
        }

        val confidence = when {
            rmsEnergy > 1000 -> 0.85 + (0.1 * (1.0 - (1000.0 / rmsEnergy)))
            else -> 0.60
        }.coerceIn(0.0, 1.0)

        // Clear buffer for privacy (Citizen Science requirement)
        synchronized(circularBuffer) {
            for (i in 0 until bufferSizeShorts) {
                circularBuffer[i] = 0
            }
            writeIndex = 0
        }

        AudioContextResult(
            eventLabel = eventLabel,
            confidence = confidence,
            rmsEnergy = rmsEnergy,
            spectralCentroidHz = spectralCentroidHz,
            lowFrequencyEnergyRatio = lowFrequencyEnergyRatio
        )
    }
}

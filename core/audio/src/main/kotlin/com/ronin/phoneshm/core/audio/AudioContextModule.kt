package com.ronin.phoneshm.core.audio

/**
 * AudioContextResult captures acoustic features extracted from a circular RAM window
 * immediately surrounding a vibration event without persisting raw audio.
 */
data class AudioContextResult(
    val eventLabel: String,
    val confidence: Double,
    val rmsEnergy: Float,
    val spectralCentroidHz: Float,
    val lowFrequencyEnergyRatio: Float,
    val captureWindowSec: String = "-2.0s to +3.0s"
)

/**
 * AudioContextModule maintains a rolling 5-second RAM circular audio buffer,
 * extracting rule-based acoustic features when triggered by vibration and flushing PCM data.
 */
interface AudioContextModule {
    /**
     * Starts continuous low-latency circular RAM audio buffer.
     */
    fun startCircularBuffer()

    /**
     * Triggers feature extraction across the buffered -2s to +3s window around transient trigger,
     * classifying acoustic source and immediately clearing raw PCM buffers.
     */
    suspend fun extractFeaturesAroundTrigger(): AudioContextResult
}

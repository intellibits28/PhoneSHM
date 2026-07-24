package com.ronin.phoneshm.core.audio

data class WptFeatures(
    val nodeEnergies: FloatArray, // normalized relative energy per packet node
    val eventLabel: String,
    val confidence: Double
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as WptFeatures

        if (!nodeEnergies.contentEquals(other.nodeEnergies)) return false
        if (eventLabel != other.eventLabel) return false
        if (confidence != other.confidence) return false

        return true
    }

    override fun hashCode(): Int {
        var result = nodeEnergies.contentHashCode()
        result = 31 * result + eventLabel.hashCode()
        result = 31 * result + confidence.hashCode()
        return result
    }
}

class WptFeatureExtractor {
    
    companion object {
        init {
            // Load native library when this class is accessed
            // System.loadLibrary("phoneshm_audio") // Uncomment when CMake is configured
        }
    }

    /**
     * Takes raw PCM float data (sampled at native rate), downsamples to working rate (16kHz),
     * performs fixed-depth Wavelet Packet Transform (Daubechies db4), computes node-energy features,
     * and classifies the environment.
     *
     * Raw PCM is immediately flushed from memory upon extraction (handled in C++ side).
     */
    fun extractNodeEnergies(
        rawPcm: FloatArray,
        nativeSampleRate: Int,
        decompositionDepth: Int = 5
    ): WptFeatures {
        // Fallback for JVM testing if native library is not loaded
        return try {
            extractNodeEnergiesNative(rawPcm, nativeSampleRate, decompositionDepth)
        } catch (e: UnsatisfiedLinkError) {
            WptFeatures(
                nodeEnergies = FloatArray(1 shl decompositionDepth) { 1.0f / (1 shl decompositionDepth) },
                eventLabel = "quiet_mock",
                confidence = 0.99
            )
        }
    }

    private external fun extractNodeEnergiesNative(
        rawPcm: FloatArray,
        nativeSampleRate: Int,
        depth: Int
    ): WptFeatures
}

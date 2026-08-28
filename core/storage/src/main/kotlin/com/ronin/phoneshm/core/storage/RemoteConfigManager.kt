package com.ronin.phoneshm.core.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Manages local app settings for DSP thresholds.
 * Replaces Firebase Remote Config for cost saving.
 */
object RemoteConfigManager {
    private const val TAG = "RemoteConfigManager"
    private const val PREFS_NAME = "phoneshm_config"

    // Default values (fallback)
    private const val DEFAULT_PEAK_TO_RMS = 5.0f
    private const val DEFAULT_AMBIENT_SNR = 1.0f
    private const val DEFAULT_SPECTRAL_SANITY = 0.15f
    private const val DEFAULT_GAP_MISSING_RATIO = 0.05f
    private const val DEFAULT_MIN_RMS_MULTIPLIER = 0.1f

    // Keys
    private const val KEY_PEAK_TO_RMS = "PEAK_TO_RMS_THRESHOLD"
    private const val KEY_AMBIENT_SNR = "AMBIENT_SNR_THRESHOLD_DB"
    private const val KEY_SPECTRAL_SANITY = "SPECTRAL_SANITY_THRESHOLD"
    private const val KEY_GAP_MISSING_RATIO = "GAP_MISSING_TIME_RATIO_THRESHOLD"
    private const val KEY_MIN_RMS_MULTIPLIER = "MIN_RMS_MULTIPLIER"

    private lateinit var prefs: SharedPreferences

    fun initialize(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Ensure defaults are populated if they don't exist
        if (!prefs.contains(KEY_PEAK_TO_RMS)) {
            prefs.edit()
                .putFloat(KEY_PEAK_TO_RMS, DEFAULT_PEAK_TO_RMS)
                .putFloat(KEY_AMBIENT_SNR, DEFAULT_AMBIENT_SNR)
                .putFloat(KEY_SPECTRAL_SANITY, DEFAULT_SPECTRAL_SANITY)
                .putFloat(KEY_GAP_MISSING_RATIO, DEFAULT_GAP_MISSING_RATIO)
                .putFloat(KEY_MIN_RMS_MULTIPLIER, DEFAULT_MIN_RMS_MULTIPLIER)
                .apply()
            Log.d(TAG, "Populated default local config settings")
        }
    }

    private fun getSafeDouble(key: String, default: Float): Double {
        return if (::prefs.isInitialized) {
            prefs.getFloat(key, default).toDouble()
        } else {
            default.toDouble()
        }
    }

    val peakToRmsThreshold: Double
        get() = clamp(
            getSafeDouble(KEY_PEAK_TO_RMS, DEFAULT_PEAK_TO_RMS),
            2.0, 20.0,
            DEFAULT_PEAK_TO_RMS.toDouble()
        )

    val ambientSnrThresholdDb: Float
        get() = clamp(
            getSafeDouble(KEY_AMBIENT_SNR, DEFAULT_AMBIENT_SNR),
            0.1, 10.0,
            DEFAULT_AMBIENT_SNR.toDouble()
        ).toFloat()

    val spectralSanityThreshold: Double
        get() = clamp(
            getSafeDouble(KEY_SPECTRAL_SANITY, DEFAULT_SPECTRAL_SANITY),
            0.01, 0.5,
            DEFAULT_SPECTRAL_SANITY.toDouble()
        )

    val gapMissingTimeRatioThreshold: Double
        get() = clamp(
            getSafeDouble(KEY_GAP_MISSING_RATIO, DEFAULT_GAP_MISSING_RATIO),
            0.01, 0.3,
            DEFAULT_GAP_MISSING_RATIO.toDouble()
        )

    val minRmsMultiplier: Double
        get() = clamp(
            getSafeDouble(KEY_MIN_RMS_MULTIPLIER, DEFAULT_MIN_RMS_MULTIPLIER),
            0.01, 1.0,
            DEFAULT_MIN_RMS_MULTIPLIER.toDouble()
        )

    fun updateConfig(
        peakToRms: Float? = null,
        ambientSnr: Float? = null,
        spectralSanity: Float? = null,
        gapMissingRatio: Float? = null,
        minRmsMult: Float? = null
    ) {
        val editor = prefs.edit()
        peakToRms?.let { editor.putFloat(KEY_PEAK_TO_RMS, it) }
        ambientSnr?.let { editor.putFloat(KEY_AMBIENT_SNR, it) }
        spectralSanity?.let { editor.putFloat(KEY_SPECTRAL_SANITY, it) }
        gapMissingRatio?.let { editor.putFloat(KEY_GAP_MISSING_RATIO, it) }
        minRmsMult?.let { editor.putFloat(KEY_MIN_RMS_MULTIPLIER, it) }
        editor.apply()
    }

    private fun clamp(value: Double, min: Double, max: Double, default: Double): Double {
        return if (value in min..max) {
            value
        } else {
            Log.w(TAG, "Fetched value $value is out of bounds [$min, $max]. Falling back to $default")
            default
        }
    }
}

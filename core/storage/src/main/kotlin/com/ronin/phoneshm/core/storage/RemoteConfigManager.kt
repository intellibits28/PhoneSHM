package com.ronin.phoneshm.core.storage

import android.util.Log
import com.google.firebase.ktx.Firebase
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.ktx.remoteConfig
import com.google.firebase.remoteconfig.ktx.remoteConfigSettings

/**
 * Manages Firebase Remote Config for DSP thresholds.
 * Fetches values with a 1-hour cache TTL and clamps them to sane limits.
 */
object RemoteConfigManager {
    private const val TAG = "RemoteConfigManager"

    // Default values (fallback if remote fetch fails)
    private const val DEFAULT_PEAK_TO_RMS = 5.0
    private const val DEFAULT_AMBIENT_SNR = 1.0
    private const val DEFAULT_SPECTRAL_SANITY = 0.15
    private const val DEFAULT_GAP_MISSING_RATIO = 0.05

    // Remote Config Keys
    private const val KEY_PEAK_TO_RMS = "PEAK_TO_RMS_THRESHOLD"
    private const val KEY_AMBIENT_SNR = "AMBIENT_SNR_THRESHOLD_DB"
    private const val KEY_SPECTRAL_SANITY = "SPECTRAL_SANITY_THRESHOLD"
    private const val KEY_GAP_MISSING_RATIO = "GAP_MISSING_TIME_RATIO_THRESHOLD"

    private lateinit var remoteConfig: FirebaseRemoteConfig

    fun initialize(isDebug: Boolean = false) {
        remoteConfig = Firebase.remoteConfig
        val configSettings = remoteConfigSettings {
            minimumFetchIntervalInSeconds = if (isDebug) 0 else 3600 // 0s in debug, 1 hour in release
        }
        remoteConfig.setConfigSettingsAsync(configSettings)

        // Set in-app fallback defaults
        val defaults = mapOf(
            KEY_PEAK_TO_RMS to DEFAULT_PEAK_TO_RMS,
            KEY_AMBIENT_SNR to DEFAULT_AMBIENT_SNR,
            KEY_SPECTRAL_SANITY to DEFAULT_SPECTRAL_SANITY,
            KEY_GAP_MISSING_RATIO to DEFAULT_GAP_MISSING_RATIO
        )
        remoteConfig.setDefaultsAsync(defaults)

        // Fetch and activate async (on startup)
        remoteConfig.fetchAndActivate().addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val updated = task.result
                Log.d(TAG, "Config params updated: $updated")
            } else {
                Log.w(TAG, "Config fetch failed")
            }
        }
    }

    private fun getSafeDouble(key: String, default: Double): Double {
        return if (::remoteConfig.isInitialized) {
            remoteConfig.getDouble(key)
        } else {
            default
        }
    }

    val peakToRmsThreshold: Double
        get() = clamp(
            getSafeDouble(KEY_PEAK_TO_RMS, DEFAULT_PEAK_TO_RMS),
            2.0, 20.0,
            DEFAULT_PEAK_TO_RMS
        )

    val ambientSnrThresholdDb: Float
        get() = clamp(
            getSafeDouble(KEY_AMBIENT_SNR, DEFAULT_AMBIENT_SNR),
            0.1, 10.0,
            DEFAULT_AMBIENT_SNR
        ).toFloat()

    val spectralSanityThreshold: Double
        get() = clamp(
            getSafeDouble(KEY_SPECTRAL_SANITY, DEFAULT_SPECTRAL_SANITY),
            0.01, 0.5,
            DEFAULT_SPECTRAL_SANITY
        )

    val gapMissingTimeRatioThreshold: Double
        get() = clamp(
            getSafeDouble(KEY_GAP_MISSING_RATIO, DEFAULT_GAP_MISSING_RATIO),
            0.01, 0.3,
            DEFAULT_GAP_MISSING_RATIO
        )

    private fun clamp(value: Double, min: Double, max: Double, default: Double): Double {
        return if (value in min..max) {
            value
        } else {
            Log.w(TAG, "Fetched value $value is out of bounds [$min, $max]. Falling back to $default")
            default
        }
    }
}

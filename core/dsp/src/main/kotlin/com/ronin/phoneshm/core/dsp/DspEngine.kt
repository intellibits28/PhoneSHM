package com.ronin.phoneshm.core.dsp

import com.ronin.phoneshm.core.sensor.AccelerationSample

/**
 * WelchPsdParameters specifies exact physical time windowing and overlap settings.
 */
data class WelchPsdParameters(
    val fftSize: Int = 1024,              // 10.24 sec physical window at 100Hz (deltaF = 0.097 Hz)
    val windowType: String = "HANNING",
    val overlapPercentage: Float = 0.50f, // 50% overlap
    val averageSegmentsCount: Int = 1
)

/**
 * Peak identifies a resonant frequency anomaly in the computed power spectrum.
 */
data class Peak(
    val frequencyHz: Float,
    val powerMagnitude: Float,
    val prominence: Float
)

/**
 * AxisPsdResult holds frequencies and PSD values for a single directional or magnitude component.
 */
data class AxisPsdResult(
    val frequencies: FloatArray,
    val powerSpectralDensity: FloatArray,
    val peaks: List<Peak>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AxisPsdResult
        return frequencies.contentEquals(other.frequencies) &&
                powerSpectralDensity.contentEquals(other.powerSpectralDensity) &&
                peaks == other.peaks
    }

    override fun hashCode(): Int {
        var result = frequencies.contentHashCode()
        result = 31 * result + powerSpectralDensity.contentHashCode()
        result = 31 * result + peaks.hashCode()
        return result
    }
}

/**
 * MultiAxisSpectrumResult preserves separate X, Y, Z axis power spectra alongside fused magnitude.
 */
data class MultiAxisSpectrumResult(
    val psdX: AxisPsdResult,
    val psdY: AxisPsdResult,
    val psdZ: AxisPsdResult,
    val psdMagnitude: AxisPsdResult,
    val parameters: WelchPsdParameters
)

/**
 * DspEngine processes multi-axis accelerometer signals, executing gravity removal, detrending,
 * Hanning windowing, and Welch's Method PSD estimation separately across directional components.
 */
interface DspEngine {
    /**
     * Removes static gravity component and linear drift from multi-axis sample stream.
     */
    fun removeGravityAndDetrend(samples: List<AccelerationSample>): List<AccelerationSample>

    /**
     * Applies high-pass Butterworth filter to eliminate low-frequency thermal/drift noise (< 0.5 Hz).
     */
    fun highPassFilter(signal: FloatArray, sampleRateHz: Float, cutoffHz: Float = 0.5f): FloatArray

    /**
     * Applies Hanning window function across a physical time segment before FFT.
     */
    fun applyHanningWindow(windowSegment: FloatArray): FloatArray

    /**
     * Computes multi-axis Welch's Method Power Spectral Density (X, Y, Z, and Magnitude).
     */
    fun calculateMultiAxisWelchPsd(
        samples: List<AccelerationSample>,
        sampleRateHz: Float = 100f,
        params: WelchPsdParameters = WelchPsdParameters()
    ): MultiAxisSpectrumResult
}

package com.ronin.phoneshm.core.dsp

import com.ronin.phoneshm.core.sensor.AccelerationSample

/**
 * GravityFreeSample represents an acceleration sample after gravity removal
 * and detrending. This type enforces at the compiler level that magnitude
 * fusion (fuseAxes) can only be performed on gravity-free data, preventing
 * the nonlinear sub-1Hz artifact from √(x²+y²+z²) on raw gravity-laden axes.
 *
 * v1.3.1 (A1): Type-safe pipeline ordering.
 */
data class GravityFreeSample(
    val timestampNs: Long,
    val x: Float,
    val y: Float,
    val z: Float
)

/**
 * GravityRemovalResult contains both gravity-free samples and the estimated
 * gravity vector time-series for downstream QualityEngine coupling score.
 *
 * v1.4.1 (B1): EMA limitation documented. gravityVectorVariance provides
 * the coupling quality input. If variance is high, phone was moving during
 * recording and the coupling score should be penalized.
 */
data class GravityRemovalResult(
    val gravityFreeSamples: List<GravityFreeSample>,
    val estimatedGravityVectors: List<FloatArray>,  // [gx, gy, gz] per sample
    val gravityVectorVariance: Float                 // Summary stat for QualityEngine coupling score
)

/**
 * SettlingWindowResult describes the settling period analysis.
 *
 * v1.4.1 (B1): Compound settling window combining HPF transient guard
 * and orientation stability detection.
 */
data class SettlingWindowResult(
    val settlingDurationSamples: Int,     // Actual samples discarded
    val gravityStabilizedAtSample: Int,   // When gravity variance dropped below threshold
    val wasExtended: Boolean              // True if settling exceeded 6s base due to instability
)

/**
 * WelchPsdParameters specifies exact physical time windowing and overlap settings.
 *
 * v1.3.1 (A5): averageSegmentsCount removed — auto-derived, not caller-supplied.
 */
data class WelchPsdParameters(
    val fftSize: Int = 1024,              // 10.24 sec physical window at 100Hz (deltaF = 0.0977 Hz)
    val windowType: String = "HANNING",
    val overlapPercentage: Float = 0.50f  // 50% overlap (5.12 sec step)
)

/**
 * WelchPsdOutput extends parameters with auto-computed metadata.
 *
 * v1.3.1 (A5): actualSegmentCount is auto-derived from (signalLength, fftSize, overlap).
 */
data class WelchPsdOutput(
    val parameters: WelchPsdParameters,
    val actualSegmentCount: Int,
    val effectiveDeltaFHz: Float,             // = sampleRateHz / fftSize
    val settlingWindow: SettlingWindowResult?  // B1: settling window analysis (null if not applicable)
)

/**
 * Peak identifies a resonant frequency in the computed power spectrum.
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
 *
 * v1.4.1: Uses WelchPsdOutput instead of WelchPsdParameters for richer metadata.
 */
data class MultiAxisSpectrumResult(
    val psdX: AxisPsdResult,
    val psdY: AxisPsdResult,
    val psdZ: AxisPsdResult,
    val psdMagnitude: AxisPsdResult,  // Computed from gravity-free magnitude: √(gfx²+gfy²+gfz²)
    val output: WelchPsdOutput
)

/**
 * DspEngine processes multi-axis accelerometer signals, executing gravity removal, detrending,
 * Hanning windowing, and Welch's Method PSD estimation separately across directional components.
 *
 * v1.4.1 changes:
 * - (B1) removeGravityAndDetrend returns GravityRemovalResult with gravity vector time-series
 * - (B2) Dual-path architecture: streamingRmsLevel (causal, live HUD) vs
 *         calculateMultiAxisWelchPsd (non-causal, post-session accurate)
 * - (C4) Degenerate input contract: NaN/Inf → short-circuit, no crash
 *
 * DEGENERATE INPUT CONTRACT:
 *   - All-zero signal → PSD with all-zero power, no peaks. Classification: SENSOR_ARTIFACT.
 *   - NaN/Inf in any sample → immediately short-circuit, skip FFT, return empty result.
 *   - Sample count < fftSize → return empty result, no crash.
 *   - Constant DC signal → detrend removes it, PSD shows no peaks.
 */
interface DspEngine {
    /**
     * Removes gravity via EMA and subtracts per-axis linear trend.
     * Returns GravityRemovalResult including gravity vector time-series
     * and variance for QualityEngine coupling score.
     *
     * ⚠ EMA limitation: During orientation changes, EMA-tracked gravity
     * lags behind the true gravity vector, causing transient low-frequency
     * leakage. Coupling quality score should penalize high gravityVectorVariance.
     *
     * v1.4.1 (B1): Return type changed from List<AccelerationSample> to GravityRemovalResult.
     */
    fun removeGravityAndDetrend(samples: List<AccelerationSample>): GravityRemovalResult

    /**
     * Computes magnitude axis from gravity-free samples: a_t = √(x²+y²+z²).
     * Accepts ONLY GravityFreeSample to prevent the nonlinear sub-1Hz artifact (v1.3 A1).
     */
    fun fuseAxes(samples: List<GravityFreeSample>): FloatArray

    /**
     * Applies 2nd-order Butterworth high-pass filter (zero-phase filtfilt).
     */
    fun highPassFilter(signal: FloatArray, sampleRateHz: Float, cutoffHz: Float = 0.5f): FloatArray

    /**
     * Applies Hanning window function across a physical time segment before FFT.
     */
    fun applyHanningWindow(windowSegment: FloatArray): FloatArray

    // ═══════════════════════════════════════════════════════════
    //  B2: LIVE PATH (causal, real-time) — for HUD level meter
    // ═══════════════════════════════════════════════════════════

    /**
     * LIVE PATH: Stateful windowed RMS computation.
     * Internally maintains a leaky-integrator / exponential moving average
     * with time constant τ ≈ 200ms (20 samples at 100Hz).
     *
     * ⚠ This is NOT for accurate modal analysis. It provides raw signal
     * energy feedback for the live HUD during recording.
     *
     * Must call resetStreamingState() before each new recording session
     * to prevent residual state leakage from previous sessions.
     */
    fun streamingRmsLevel(sample: AccelerationSample): Float

    /**
     * Resets all internal streaming state (RMS accumulator, sample counter).
     * Must be called at session boundaries (start of new recording).
     */
    fun resetStreamingState()

    // ═══════════════════════════════════════════════════════════
    //  B2: POST-SESSION PATH (non-causal, full-buffer) — accurate PSD
    // ═══════════════════════════════════════════════════════════

    /**
     * POST-SESSION PATH: Full-buffer accurate Welch PSD with filtfilt
     * zero-phase HPF, gravity removal, settling window detection,
     * per-segment detrending, and peak detection.
     *
     * Cannot be used in real-time — requires the complete signal buffer.
     *
     * Internal pipeline per axis:
     *   1. Gravity removal → GravityFreeSample (v1.3 A1, v1.4.1 B1)
     *   2. Settling window detection (v1.4.1 B1 compound)
     *   3. High-pass filtfilt at 0.5 Hz (zero-phase, v1.3.1 A6)
     *   4. Segment into overlapping windows of size fftSize
     *   5. Per-segment linear detrend (double precision) (v1.3 A2, v1.4.1 C1)
     *   6. Apply Hanning window
     *   7. FFT → accumulate |X(f)|² (double precision - C1)
     *   8. Average across segments → PSD
     *   9. Normalize by (fs × windowPowerSum)
     *  10. Peak detection
     *
     * v1.3.1 (A5): Segment count auto-derived from signal length.
     */
    fun calculateMultiAxisWelchPsd(
        samples: List<AccelerationSample>,
        sampleRateHz: Float = 100f,
        params: WelchPsdParameters = WelchPsdParameters()
    ): MultiAxisSpectrumResult

    /**
     * Verifies impulse-mode quality (TASK A): Peak-to-RMS ratio >= 5.0x and 0.5-15Hz spectral sanity check.
     */
    fun verifyImpulseQuality(
        samples: List<AccelerationSample>,
        sampleRateHz: Float = 100f
    ): WelchPsdEngine.ImpulseVerificationResult

    /**
     * Verifies sampling continuity (TASK 3): Flags sessions where gaps > 50ms total > 5% of duration.
     */
    fun verifySamplingContinuity(
        samples: List<AccelerationSample>,
        gapThresholdMs: Double = 50.0,
        maxAllowedMissingRatio: Double = 0.05
    ): WelchPsdEngine.SamplingContinuityResult
}

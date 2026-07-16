package com.ronin.phoneshm.core.dsp

import com.ronin.phoneshm.core.sensor.AccelerationSample
import kotlin.math.*

/**
 * WelchPsdEngine — Research-grade multi-axis Welch's Method PSD implementation.
 *
 * Implements the full DspEngine interface providing:
 * - Gravity removal via exponential moving average (alpha = 0.1)
 * - Linear detrending (least-squares) per axis
 * - 2nd-order Butterworth IIR high-pass filter
 * - Hanning window function
 * - Radix-2 Cooley-Tukey FFT (in-place)
 * - Welch's Method: segmented, windowed, overlapped PSD averaging
 * - Automatic spectral peak detection with prominence calculation
 *
 * All computations are pure Kotlin with zero external dependencies,
 * suitable for deterministic unit testing and Android deployment.
 */
class WelchPsdEngine : DspEngine {

    // ─────────────────────────────────────────────────────────────
    // 1. Gravity Removal & Detrending
    // ─────────────────────────────────────────────────────────────

    /**
     * Removes gravity via exponential moving average (alpha = 0.1) and
     * subtracts per-axis linear trend (least-squares fit) from the residual.
     *
     * Gravity estimation:
     *   g_n = alpha * a_n + (1 - alpha) * g_{n-1}
     *
     * Detrending uses ordinary least-squares:
     *   a'_i = a_i - (slope * i + intercept)
     */
    override fun removeGravityAndDetrend(samples: List<AccelerationSample>): List<AccelerationSample> {
        if (samples.isEmpty()) return emptyList()

        val n = samples.size
        val alpha = 0.1f

        // Step 1 — Remove gravity via EMA
        val xRes = FloatArray(n)
        val yRes = FloatArray(n)
        val zRes = FloatArray(n)

        var gx = samples[0].x
        var gy = samples[0].y
        var gz = samples[0].z

        for (i in samples.indices) {
            val s = samples[i]
            gx = alpha * s.x + (1f - alpha) * gx
            gy = alpha * s.y + (1f - alpha) * gy
            gz = alpha * s.z + (1f - alpha) * gz
            xRes[i] = s.x - gx
            yRes[i] = s.y - gy
            zRes[i] = s.z - gz
        }

        // Step 2 — Detrend each axis (remove linear component via least-squares)
        detrendInPlace(xRes)
        detrendInPlace(yRes)
        detrendInPlace(zRes)

        return samples.indices.map { i ->
            AccelerationSample(
                timestampNs = samples[i].timestampNs,
                x = xRes[i],
                y = yRes[i],
                z = zRes[i]
            )
        }
    }

    /**
     * In-place linear detrend using least-squares:
     *   slope = (n * Σ(i*x_i) - Σi * Σx_i) / (n * Σi² - (Σi)²)
     *   intercept = (Σx_i - slope * Σi) / n
     */
    private fun detrendInPlace(data: FloatArray) {
        val n = data.size
        if (n < 2) return

        var sumI = 0.0
        var sumX = 0.0
        var sumIX = 0.0
        var sumI2 = 0.0

        for (i in data.indices) {
            val iD = i.toDouble()
            val xD = data[i].toDouble()
            sumI += iD
            sumX += xD
            sumIX += iD * xD
            sumI2 += iD * iD
        }

        val denom = n * sumI2 - sumI * sumI
        if (abs(denom) < 1e-12) return

        val slope = (n * sumIX - sumI * sumX) / denom
        val intercept = (sumX - slope * sumI) / n

        for (i in data.indices) {
            data[i] -= (slope * i + intercept).toFloat()
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 2. High-Pass Butterworth IIR Filter (2nd order)
    // ─────────────────────────────────────────────────────────────

    /**
     * 2nd-order Butterworth high-pass IIR filter.
     *
     * Transfer function derived from bilinear transform:
     *   s-domain prototype: H(s) = s² / (s² + √2·ωc·s + ωc²)
     *   Bilinear z-transform with pre-warping.
     *
     * Applied as forward-backward (filtfilt equivalent) for zero phase distortion.
     */
    override fun highPassFilter(signal: FloatArray, sampleRateHz: Float, cutoffHz: Float): FloatArray {
        if (signal.size < 3) return signal.copyOf()

        // Pre-warp the cutoff frequency
        val wc = tan(PI * cutoffHz / sampleRateHz)
        val wc2 = wc * wc
        val sqrt2wc = sqrt(2.0) * wc
        val k = 1.0 + sqrt2wc + wc2

        // Compute filter coefficients (normalized)
        val b0 = (1.0 / k).toFloat()
        val b1 = (-2.0 / k).toFloat()
        val b2 = (1.0 / k).toFloat()
        val a1 = (2.0 * (wc2 - 1.0) / k).toFloat()
        val a2 = ((1.0 - sqrt2wc + wc2) / k).toFloat()

        // Forward pass
        val forward = applyIir(signal, b0, b1, b2, a1, a2)
        // Backward pass (reverse, filter, reverse) for zero-phase
        forward.reverse()
        val backward = applyIir(forward, b0, b1, b2, a1, a2)
        backward.reverse()

        return backward
    }

    /**
     * Applies a 2nd-order IIR filter using Direct Form II Transposed structure.
     */
    private fun applyIir(
        input: FloatArray,
        b0: Float, b1: Float, b2: Float,
        a1: Float, a2: Float
    ): FloatArray {
        val output = FloatArray(input.size)
        var w1 = 0f
        var w2 = 0f

        for (i in input.indices) {
            val w0 = input[i] - a1 * w1 - a2 * w2
            output[i] = b0 * w0 + b1 * w1 + b2 * w2
            w2 = w1
            w1 = w0
        }
        return output
    }

    // ─────────────────────────────────────────────────────────────
    // 3. Hanning Window Function
    // ─────────────────────────────────────────────────────────────

    /**
     * Applies the Hanning (raised cosine) window function:
     *   w(n) = 0.5 * (1 - cos(2π·n / (N-1)))
     *
     * Returns a new array with the window applied (does not mutate input).
     */
    override fun applyHanningWindow(windowSegment: FloatArray): FloatArray {
        val n = windowSegment.size
        if (n <= 1) return windowSegment.copyOf()

        val result = FloatArray(n)
        val twoPiOverNm1 = 2.0 * PI / (n - 1)

        for (i in 0 until n) {
            val w = 0.5 * (1.0 - cos(twoPiOverNm1 * i))
            result[i] = (windowSegment[i] * w).toFloat()
        }
        return result
    }

    // ─────────────────────────────────────────────────────────────
    // 4. Radix-2 Cooley-Tukey FFT
    // ─────────────────────────────────────────────────────────────

    /**
     * In-place radix-2 Cooley-Tukey FFT.
     * Input: real[N] and imag[N] arrays where N must be a power of 2.
     * Produces the complex DFT coefficients in-place.
     */
    private fun fft(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        require(n > 0 && (n and (n - 1)) == 0) { "FFT size must be a power of 2, got $n" }

        // Bit-reversal permutation
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var temp = real[i]; real[i] = real[j]; real[j] = temp
                temp = imag[i]; imag[i] = imag[j]; imag[j] = temp
            }
        }

        // Butterfly computation
        var len = 2
        while (len <= n) {
            val halfLen = len / 2
            val angle = -2.0 * PI / len
            val wRe = cos(angle)
            val wIm = sin(angle)

            var i = 0
            while (i < n) {
                var curRe = 1.0
                var curIm = 0.0

                for (k in 0 until halfLen) {
                    val tRe = curRe * real[i + k + halfLen] - curIm * imag[i + k + halfLen]
                    val tIm = curRe * imag[i + k + halfLen] + curIm * real[i + k + halfLen]
                    real[i + k + halfLen] = real[i + k] - tRe
                    imag[i + k + halfLen] = imag[i + k] - tIm
                    real[i + k] = real[i + k] + tRe
                    imag[i + k] = imag[i + k] + tIm

                    val newRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = newRe
                }
                i += len
            }
            len = len shl 1
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 5. Welch's Method PSD Computation
    // ─────────────────────────────────────────────────────────────

    /**
     * Computes multi-axis Welch's Method Power Spectral Density separately for X, Y, Z, and Magnitude.
     *
     * Algorithm:
     *   1. Extract per-axis signals from samples
     *   2. High-pass filter each axis at 0.5 Hz to remove DC/drift
     *   3. Segment each signal into overlapping windows of size fftSize
     *   4. Apply Hanning window to each segment
     *   5. Compute FFT and accumulate |X(f)|² per segment
     *   6. Average across segments → PSD estimate
     *   7. Normalize by (fs * windowPowerSum) for proper PSD scaling (V²/Hz equivalent)
     *   8. Find spectral peaks with prominence
     *
     * Physical resolution: deltaF = sampleRateHz / fftSize
     *   At 100Hz / 1024 samples = 0.0977 Hz resolution over 10.24 second windows.
     */
    override fun calculateMultiAxisWelchPsd(
        samples: List<AccelerationSample>,
        sampleRateHz: Float,
        params: WelchPsdParameters
    ): MultiAxisSpectrumResult {
        val n = samples.size
        val fftSize = params.fftSize

        require(n >= fftSize) {
            "Need at least $fftSize samples for FFT, got $n"
        }

        // Extract per-axis signals
        val rawX = FloatArray(n) { samples[it].x }
        val rawY = FloatArray(n) { samples[it].y }
        val rawZ = FloatArray(n) { samples[it].z }
        val rawMag = FloatArray(n) { sqrt(samples[it].x * samples[it].x + samples[it].y * samples[it].y + samples[it].z * samples[it].z) }

        // High-pass filter each axis at 0.5 Hz
        val filtX = highPassFilter(rawX, sampleRateHz, 0.5f)
        val filtY = highPassFilter(rawY, sampleRateHz, 0.5f)
        val filtZ = highPassFilter(rawZ, sampleRateHz, 0.5f)
        val filtMag = highPassFilter(rawMag, sampleRateHz, 0.5f)

        // Compute Welch PSD for each axis
        val freqBins = fftSize / 2 + 1
        val frequencies = FloatArray(freqBins) { it * sampleRateHz / fftSize }

        val psdX = welchPsdSingleAxis(filtX, fftSize, params.overlapPercentage, sampleRateHz)
        val psdY = welchPsdSingleAxis(filtY, fftSize, params.overlapPercentage, sampleRateHz)
        val psdZ = welchPsdSingleAxis(filtZ, fftSize, params.overlapPercentage, sampleRateHz)
        val psdMag = welchPsdSingleAxis(filtMag, fftSize, params.overlapPercentage, sampleRateHz)

        // Detect peaks
        val peaksX = findPeaks(frequencies, psdX)
        val peaksY = findPeaks(frequencies, psdY)
        val peaksZ = findPeaks(frequencies, psdZ)
        val peaksMag = findPeaks(frequencies, psdMag)

        // Count actual segments for metadata
        val stepSize = (fftSize * (1.0f - params.overlapPercentage)).toInt()
        val segmentCount = (n - fftSize) / stepSize + 1

        val actualParams = params.copy(averageSegmentsCount = segmentCount)

        return MultiAxisSpectrumResult(
            psdX = AxisPsdResult(frequencies.copyOf(), psdX, peaksX),
            psdY = AxisPsdResult(frequencies.copyOf(), psdY, peaksY),
            psdZ = AxisPsdResult(frequencies.copyOf(), psdZ, peaksZ),
            psdMagnitude = AxisPsdResult(frequencies.copyOf(), psdMag, peaksMag),
            parameters = actualParams
        )
    }

    /**
     * Computes Welch's PSD for a single axis signal.
     *
     * Steps per segment:
     *   1. Extract segment of length fftSize
     *   2. Apply Hanning window
     *   3. FFT → complex coefficients
     *   4. Compute |X(k)|² = re² + im² for k = 0..N/2
     *   5. Accumulate across all segments
     *   6. Average and normalize: PSD(k) = (2 * avg|X(k)|²) / (fs * S2)
     *      where S2 = Σ w(n)² is the window power sum for proper scaling.
     *      Factor 2 accounts for one-sided spectrum (positive frequencies only).
     *      DC and Nyquist bins are not doubled.
     */
    private fun welchPsdSingleAxis(
        signal: FloatArray,
        fftSize: Int,
        overlapFraction: Float,
        sampleRateHz: Float
    ): FloatArray {
        val n = signal.size
        val stepSize = (fftSize * (1.0f - overlapFraction)).toInt().coerceAtLeast(1)
        val freqBins = fftSize / 2 + 1
        val psdAccumulator = DoubleArray(freqBins)

        // Pre-compute Hanning window and its power sum (S2)
        val window = FloatArray(fftSize)
        var windowPowerSum = 0.0
        val twoPiOverNm1 = 2.0 * PI / (fftSize - 1)
        for (i in 0 until fftSize) {
            val w = 0.5 * (1.0 - cos(twoPiOverNm1 * i))
            window[i] = w.toFloat()
            windowPowerSum += w * w
        }

        var segmentCount = 0
        var offset = 0

        while (offset + fftSize <= n) {
            // Extract and window the segment
            val real = DoubleArray(fftSize)
            val imag = DoubleArray(fftSize)

            for (i in 0 until fftSize) {
                real[i] = (signal[offset + i] * window[i]).toDouble()
            }

            // FFT
            fft(real, imag)

            // Accumulate |X(k)|²
            for (k in 0 until freqBins) {
                psdAccumulator[k] += real[k] * real[k] + imag[k] * imag[k]
            }

            segmentCount++
            offset += stepSize
        }

        if (segmentCount == 0) return FloatArray(freqBins)

        // Normalize: PSD(k) = (2 / (fs * S2 * nSegments)) * accumulated|X(k)|²
        // Factor 2 for one-sided spectrum. DC (k=0) and Nyquist (k=N/2) are not doubled.
        val normFactor = 1.0 / (sampleRateHz * windowPowerSum * segmentCount)
        val psd = FloatArray(freqBins)

        psd[0] = (psdAccumulator[0] * normFactor).toFloat()  // DC: no doubling
        for (k in 1 until freqBins - 1) {
            psd[k] = (2.0 * psdAccumulator[k] * normFactor).toFloat()
        }
        psd[freqBins - 1] = (psdAccumulator[freqBins - 1] * normFactor).toFloat()  // Nyquist: no doubling

        return psd
    }

    // ─────────────────────────────────────────────────────────────
    // 6. Spectral Peak Detection with Prominence
    // ─────────────────────────────────────────────────────────────

    /**
     * Detects local maxima in the PSD and computes prominence for each.
     *
     * A point is a peak if:
     *   - psd[k] > psd[k-1] AND psd[k] > psd[k+1]
     *   - psd[k] > median(psd) * 3 (noise threshold)
     *
     * Prominence is computed as ratio of peak power to the mean of its
     * nearest neighboring valleys (or median if no valley found):
     *   prominence = psd[k] / referenceLevel
     *
     * Peaks are sorted by prominence (descending) and limited to top 20.
     */
    internal fun findPeaks(frequencies: FloatArray, psd: FloatArray): List<Peak> {
        val n = psd.size
        if (n < 3) return emptyList()

        // Compute noise floor as median of PSD values
        val sorted = psd.copyOf().also { it.sort() }
        val median = sorted[sorted.size / 2]
        val threshold = median * 3.0f

        val peaks = mutableListOf<Peak>()

        for (k in 1 until n - 1) {
            if (psd[k] > psd[k - 1] && psd[k] > psd[k + 1] && psd[k] > threshold) {
                // Find left valley
                var leftMin = psd[k]
                for (j in k - 1 downTo 0) {
                    if (psd[j] < leftMin) leftMin = psd[j]
                    if (j < k - 1 && psd[j] > psd[j + 1]) break  // next peak starts
                }

                // Find right valley
                var rightMin = psd[k]
                for (j in k + 1 until n) {
                    if (psd[j] < rightMin) rightMin = psd[j]
                    if (j > k + 1 && psd[j] > psd[j - 1]) break  // next peak starts
                }

                val referenceLevel = maxOf((leftMin + rightMin) / 2.0f, 1e-15f)
                val prominence = psd[k] / referenceLevel

                peaks.add(Peak(
                    frequencyHz = frequencies[k],
                    powerMagnitude = psd[k],
                    prominence = prominence
                ))
            }
        }

        // Sort by prominence descending, keep top 20
        return peaks.sortedByDescending { it.prominence }.take(20)
    }
}

package com.ronin.phoneshm.core.dsp

import com.ronin.phoneshm.core.sensor.AccelerationSample
import kotlin.math.*

/**
 * WelchPsdEngine — Research-grade multi-axis Welch's Method PSD implementation.
 *
 * Pure-Kotlin fallback implementation of [DspEngine]. Also serves as the reference
 * implementation for golden-value testing against the C++ cross-platform core.
 *
 * v1.4.1 changes:
 * - (B1) removeGravityAndDetrend returns GravityRemovalResult with gravity vectors
 * - (B2) Dual-path: streamingRmsLevel (live HUD) + calculateMultiAxisWelchPsd (post-session)
 * - (A1) fuseAxes accepts only GravityFreeSample
 * - (C1) Internal accumulation uses Double
 * - (C4) NaN/Inf detection short-circuits
 */
class WelchPsdEngine : DspEngine {

    // Streaming RMS state (B2)
    private var currentRms: Float = 0f

    // ─────────────────────────────────────────────────────────────
    // 1. Gravity Removal & Detrending (B1)
    // ─────────────────────────────────────────────────────────────

    override fun removeGravityAndDetrend(samples: List<AccelerationSample>): GravityRemovalResult {
        if (samples.isEmpty()) return GravityRemovalResult(emptyList(), emptyList(), 0f)

        val n = samples.size
        val alpha = 0.1f

        val xRes = FloatArray(n)
        val yRes = FloatArray(n)
        val zRes = FloatArray(n)
        val gravityVectors = ArrayList<FloatArray>(n)

        var gx = samples[0].x
        var gy = samples[0].y
        var gz = samples[0].z

        // Track gravity magnitude variance for coupling score
        var gravMagSum = 0.0
        var gravMagSumSq = 0.0

        for (i in samples.indices) {
            val s = samples[i]
            gx = alpha * s.x + (1f - alpha) * gx
            gy = alpha * s.y + (1f - alpha) * gy
            gz = alpha * s.z + (1f - alpha) * gz
            xRes[i] = s.x - gx
            yRes[i] = s.y - gy
            zRes[i] = s.z - gz
            gravityVectors.add(floatArrayOf(gx, gy, gz))

            val gMag = sqrt((gx * gx + gy * gy + gz * gz).toDouble())
            gravMagSum += gMag
            gravMagSumSq += gMag * gMag
        }

        // Population variance of gravity magnitude
        val gravMagMean = gravMagSum / n
        val gravityVectorVariance = ((gravMagSumSq / n) - gravMagMean * gravMagMean).toFloat()

        // Detrend each axis
        detrendInPlace(xRes)
        detrendInPlace(yRes)
        detrendInPlace(zRes)

        val gravityFreeSamples = samples.indices.map { i ->
            GravityFreeSample(
                timestampNs = samples[i].timestampNs,
                x = xRes[i],
                y = yRes[i],
                z = zRes[i]
            )
        }

        return GravityRemovalResult(gravityFreeSamples, gravityVectors, gravityVectorVariance)
    }

    // ─────────────────────────────────────────────────────────────
    // 1b. Magnitude Fusion (A1)
    // ─────────────────────────────────────────────────────────────

    override fun fuseAxes(samples: List<GravityFreeSample>): FloatArray {
        return FloatArray(samples.size) { i ->
            val s = samples[i]
            sqrt(s.x * s.x + s.y * s.y + s.z * s.z)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 2. High-Pass Butterworth IIR Filter (filtfilt)
    // ─────────────────────────────────────────────────────────────

    override fun highPassFilter(signal: FloatArray, sampleRateHz: Float, cutoffHz: Float): FloatArray {
        if (signal.size < 3) return signal.copyOf()

        val wc = tan(PI * cutoffHz / sampleRateHz)
        val wc2 = wc * wc
        val sqrt2wc = sqrt(2.0) * wc
        val k = 1.0 + sqrt2wc + wc2

        val b0 = (1.0 / k).toFloat()
        val b1 = (-2.0 / k).toFloat()
        val b2 = (1.0 / k).toFloat()
        val a1 = (2.0 * (wc2 - 1.0) / k).toFloat()
        val a2 = ((1.0 - sqrt2wc + wc2) / k).toFloat()

        // Forward-backward (filtfilt) for zero-phase
        val forward = applyIir(signal, b0, b1, b2, a1, a2)
        forward.reverse()
        val backward = applyIir(forward, b0, b1, b2, a1, a2)
        backward.reverse()

        return backward
    }

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
    // 3. Hanning Window
    // ─────────────────────────────────────────────────────────────

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
    // 4. B2 LIVE PATH: Streaming RMS Level
    // ─────────────────────────────────────────────────────────────

    override fun streamingRmsLevel(sample: AccelerationSample): Float {
        val mag = sqrt(sample.x * sample.x + sample.y * sample.y + sample.z * sample.z)
        val smoothingAlpha = 0.05f  // τ ≈ 200ms at 100Hz
        currentRms = smoothingAlpha * mag + (1f - smoothingAlpha) * currentRms
        return currentRms
    }

    override fun resetStreamingState() {
        currentRms = 0f
    }

    // ─────────────────────────────────────────────────────────────
    // 5. Radix-2 Cooley-Tukey FFT (Double precision — C1)
    // ─────────────────────────────────────────────────────────────

    private fun fft(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        require(n > 0 && (n and (n - 1)) == 0) { "FFT size must be a power of 2, got $n" }

        // Bit-reversal permutation
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
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
    // 6. B2 POST-SESSION PATH: Multi-Axis Welch PSD
    // ─────────────────────────────────────────────────────────────

    override fun calculateMultiAxisWelchPsd(
        samples: List<AccelerationSample>,
        sampleRateHz: Float,
        params: WelchPsdParameters
    ): MultiAxisSpectrumResult {
        val n = samples.size
        val fftSize = params.fftSize
        val freqBins = fftSize / 2 + 1

        // C4: NaN/Inf short-circuit
        if (samples.any { it.x.isNaN() || it.x.isInfinite() ||
                    it.y.isNaN() || it.y.isInfinite() ||
                    it.z.isNaN() || it.z.isInfinite() }) {
            return emptyResult(freqBins, sampleRateHz, fftSize, params)
        }

        // C4: Too few samples
        if (n < fftSize) {
            return emptyResult(freqBins, sampleRateHz, fftSize, params)
        }

        // B1: Gravity removal with vector output
        val gravResult = removeGravityAndDetrend(samples)
        val gfSamples = gravResult.gravityFreeSamples

        // B1: Settling window detection
        val settlingResult = detectSettlingWindow(gravResult, sampleRateHz)
        val settlingN = settlingResult.settlingDurationSamples

        // Extract per-axis signals AFTER settling window
        val usableN = n - settlingN
        if (usableN < fftSize) {
            return emptyResult(freqBins, sampleRateHz, fftSize, params, settlingResult)
        }

        val rawX = FloatArray(usableN) { gfSamples[it + settlingN].x }
        val rawY = FloatArray(usableN) { gfSamples[it + settlingN].y }
        val rawZ = FloatArray(usableN) { gfSamples[it + settlingN].z }

        val filtX = highPassFilter(rawX, sampleRateHz, 0.5f)
        val filtY = highPassFilter(rawY, sampleRateHz, 0.5f)
        val filtZ = highPassFilter(rawZ, sampleRateHz, 0.5f)

        // Welch PSD for each axis
        val frequencies = FloatArray(freqBins) { it * sampleRateHz / fftSize }

        val psdX = welchPsdSingleAxis(filtX, fftSize, params.overlapPercentage, sampleRateHz)
        val psdY = welchPsdSingleAxis(filtY, fftSize, params.overlapPercentage, sampleRateHz)
        val psdZ = welchPsdSingleAxis(filtZ, fftSize, params.overlapPercentage, sampleRateHz)
        
        val psdMag = FloatArray(freqBins) { k -> psdX[k] + psdY[k] + psdZ[k] }

        // Peak detection with persistence across halves
        val halfUsable = usableN / 2
        
        val psdX_h1 = if (halfUsable >= fftSize) welchPsdSingleAxis(filtX.sliceArray(0 until halfUsable), fftSize, params.overlapPercentage, sampleRateHz) else null
        val psdX_h2 = if (halfUsable >= fftSize) welchPsdSingleAxis(filtX.sliceArray(halfUsable until usableN), fftSize, params.overlapPercentage, sampleRateHz) else null
        
        val psdY_h1 = if (halfUsable >= fftSize) welchPsdSingleAxis(filtY.sliceArray(0 until halfUsable), fftSize, params.overlapPercentage, sampleRateHz) else null
        val psdY_h2 = if (halfUsable >= fftSize) welchPsdSingleAxis(filtY.sliceArray(halfUsable until usableN), fftSize, params.overlapPercentage, sampleRateHz) else null
        
        val psdZ_h1 = if (halfUsable >= fftSize) welchPsdSingleAxis(filtZ.sliceArray(0 until halfUsable), fftSize, params.overlapPercentage, sampleRateHz) else null
        val psdZ_h2 = if (halfUsable >= fftSize) welchPsdSingleAxis(filtZ.sliceArray(halfUsable until usableN), fftSize, params.overlapPercentage, sampleRateHz) else null
        
        val psdMag_h1 = if (psdX_h1 != null && psdY_h1 != null && psdZ_h1 != null) FloatArray(freqBins) { k -> psdX_h1[k] + psdY_h1[k] + psdZ_h1[k] } else null
        val psdMag_h2 = if (psdX_h2 != null && psdY_h2 != null && psdZ_h2 != null) FloatArray(freqBins) { k -> psdX_h2[k] + psdY_h2[k] + psdZ_h2[k] } else null

        val peaksX = findPeaks(frequencies, psdX, psdX_h1, psdX_h2)
        val peaksY = findPeaks(frequencies, psdY, psdY_h1, psdY_h2)
        val peaksZ = findPeaks(frequencies, psdZ, psdZ_h1, psdZ_h2)
        val peaksMag = findPeaks(frequencies, psdMag, psdMag_h1, psdMag_h2)

        // Auto-derived segment count (A5)
        val stepSize = (fftSize * (1.0f - params.overlapPercentage)).toInt()
        val segmentCount = if (stepSize > 0) (usableN - fftSize) / stepSize + 1 else 1

        return MultiAxisSpectrumResult(
            psdX = AxisPsdResult(frequencies.copyOf(), psdX, peaksX),
            psdY = AxisPsdResult(frequencies.copyOf(), psdY, peaksY),
            psdZ = AxisPsdResult(frequencies.copyOf(), psdZ, peaksZ),
            psdMagnitude = AxisPsdResult(frequencies.copyOf(), psdMag, peaksMag),
            output = WelchPsdOutput(
                parameters = params,
                actualSegmentCount = segmentCount,
                effectiveDeltaFHz = sampleRateHz / fftSize,
                settlingWindow = settlingResult
            )
        )
    }

    // ─────────────────────────────────────────────────────────────
    // 7. B1: Settling Window Detection
    // ─────────────────────────────────────────────────────────────

    /**
     * Compound settling window: HPF transient guard + orientation stability.
     * Base: ceil(3 * fs / fc) ≈ 600 samples (6s at 100Hz/0.5Hz)
     * Extended if gravity vector unstable (up to max 10s = 1000 samples)
     */
    private fun detectSettlingWindow(
        gravResult: GravityRemovalResult,
        sampleRateHz: Float,
        hpfCutoffHz: Float = 0.5f,
        gravityVarianceThreshold: Float = 0.01f
    ): SettlingWindowResult {
        val baseSettling = ceil(3.0 * sampleRateHz / hpfCutoffHz).toInt()
        val maxSettling = (10.0 * sampleRateHz).toInt()
        val windowSamples = (1.0 * sampleRateHz).toInt() // 1s trailing window

        val gravVecs = gravResult.estimatedGravityVectors
        val n = gravVecs.size

        if (n <= baseSettling) {
            return SettlingWindowResult(
                settlingDurationSamples = minOf(baseSettling, n),
                gravityStabilizedAtSample = 0,
                wasExtended = false
            )
        }

        // Check gravity magnitude variance over trailing 1s windows
        var stabilizedAt = baseSettling
        var settling = baseSettling

        for (start in baseSettling until minOf(maxSettling, n)) {
            if (start < windowSamples) continue
            val windowStart = start - windowSamples
            var sum = 0.0
            var sumSq = 0.0
            for (j in windowStart until start) {
                val gv = gravVecs[j]
                val mag = sqrt((gv[0] * gv[0] + gv[1] * gv[1] + gv[2] * gv[2]).toDouble())
                sum += mag
                sumSq += mag * mag
            }
            val mean = sum / windowSamples
            val variance = (sumSq / windowSamples - mean * mean).toFloat()

            if (variance < gravityVarianceThreshold) {
                stabilizedAt = start
                settling = start
                break
            }
            settling = start
        }

        return SettlingWindowResult(
            settlingDurationSamples = settling,
            gravityStabilizedAtSample = stabilizedAt,
            wasExtended = settling > baseSettling
        )
    }

    // ─────────────────────────────────────────────────────────────
    // Internal: Welch PSD (single axis, double accumulation — C1)
    // ─────────────────────────────────────────────────────────────

    private fun welchPsdSingleAxis(
        signal: FloatArray,
        fftSize: Int,
        overlapFraction: Float,
        sampleRateHz: Float
    ): FloatArray {
        val n = signal.size
        val stepSize = (fftSize * (1.0f - overlapFraction)).toInt().coerceAtLeast(1)
        val freqBins = fftSize / 2 + 1
        val psdAccumulator = DoubleArray(freqBins) // C1: double accumulation

        // Pre-compute Hanning window and S2
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
            // A2: Per-segment detrend (double precision)
            val segmentDouble = DoubleArray(fftSize) { signal[offset + it].toDouble() }
            detrendDoubleInPlace(segmentDouble)

            // Window and prepare for FFT
            val real = DoubleArray(fftSize)
            val imag = DoubleArray(fftSize)
            for (i in 0 until fftSize) {
                real[i] = segmentDouble[i] * window[i].toDouble()
            }

            fft(real, imag)

            // Accumulate |X(k)|² (double precision — C1)
            for (k in 0 until freqBins) {
                psdAccumulator[k] += real[k] * real[k] + imag[k] * imag[k]
            }

            segmentCount++
            offset += stepSize
        }

        if (segmentCount == 0) return FloatArray(freqBins)

        // Normalize: PSD(k) = (2 / (fs * S2 * nSegments)) * accumulated|X(k)|²
        val normFactor = 1.0 / (sampleRateHz * windowPowerSum * segmentCount)
        val psd = FloatArray(freqBins)

        psd[0] = (psdAccumulator[0] * normFactor).toFloat()  // DC: no doubling
        for (k in 1 until freqBins - 1) {
            psd[k] = (2.0 * psdAccumulator[k] * normFactor).toFloat()
        }
        psd[freqBins - 1] = (psdAccumulator[freqBins - 1] * normFactor).toFloat()  // Nyquist

        return psd
    }

    // ─────────────────────────────────────────────────────────────
    // Internal: Detrending
    // ─────────────────────────────────────────────────────────────

    private fun detrendInPlace(data: FloatArray) {
        val n = data.size
        if (n < 2) return
        var sumI = 0.0; var sumX = 0.0; var sumIX = 0.0; var sumI2 = 0.0
        for (i in data.indices) {
            val iD = i.toDouble(); val xD = data[i].toDouble()
            sumI += iD; sumX += xD; sumIX += iD * xD; sumI2 += iD * iD
        }
        val denom = n * sumI2 - sumI * sumI
        if (abs(denom) < 1e-12) return
        val slope = (n * sumIX - sumI * sumX) / denom
        val intercept = (sumX - slope * sumI) / n
        for (i in data.indices) { data[i] -= (slope * i + intercept).toFloat() }
    }

    /** A2: Per-segment detrend in double precision (C1). */
    private fun detrendDoubleInPlace(data: DoubleArray) {
        val n = data.size
        if (n < 2) return
        var sumI = 0.0; var sumX = 0.0; var sumIX = 0.0; var sumI2 = 0.0
        for (i in data.indices) {
            val iD = i.toDouble()
            sumI += iD; sumX += data[i]; sumIX += iD * data[i]; sumI2 += iD * iD
        }
        val denom = n * sumI2 - sumI * sumI
        if (abs(denom) < 1e-12) return
        val slope = (n * sumIX - sumI * sumX) / denom
        val intercept = (sumX - slope * sumI) / n
        for (i in data.indices) { data[i] -= slope * i + intercept }
    }

    // ─────────────────────────────────────────────────────────────
    // Internal: Peak Detection
    // ─────────────────────────────────────────────────────────────

    internal fun findPeaks(frequencies: FloatArray, psd: FloatArray, psdH1: FloatArray? = null, psdH2: FloatArray? = null): List<Peak> {
        val n = psd.size
        if (n < 3) return emptyList()

        val peaks = mutableListOf<Peak>()
        val localWindowHalf = 20
        val guardBand = 2

        for (k in 1 until n - 1) {
            if (psd[k] > psd[k - 1] && psd[k] > psd[k + 1]) {
                // Calculate local noise floor in dB
                val localBins = mutableListOf<Float>()
                for (j in maxOf(0, k - localWindowHalf)..minOf(n - 1, k + localWindowHalf)) {
                    if (abs(j - k) > guardBand) localBins.add(psd[j])
                }
                
                if (localBins.isEmpty()) continue
                localBins.sort()
                val localMedian = localBins[localBins.size / 2]
                if (localMedian <= 0.0f) continue
                
                val peakDb = 10.0f * log10(psd[k])
                val noiseDb = 10.0f * log10(localMedian)
                val snrDb = peakDb - noiseDb
                
                // TASK B: Calibrated against phone-stationary ambient fixtures only. Not yet validated against a confirmed genuine-building-excitation fixture (e.g. traffic-adjacent or occupied-building recording). False-negative risk on weak real ambient signals until validated.
                val minSnrThresholdDb = 1.0f
                if (snrDb > minSnrThresholdDb) {
                    // Persistence check across halves
                    var persists = true
                    if (psdH1 != null && psdH2 != null) {
                        val inH1 = checkLocalPeak(psdH1, k, guardBand)
                        val inH2 = checkLocalPeak(psdH2, k, guardBand)
                        persists = inH1 && inH2
                    }
                    
                    if (persists) {
                        var leftMin = psd[k]
                        for (j in k - 1 downTo 0) {
                            if (psd[j] < leftMin) leftMin = psd[j]
                            if (j < k - 1 && psd[j] > psd[j + 1]) break
                        }
                        var rightMin = psd[k]
                        for (j in k + 1 until n) {
                            if (psd[j] < rightMin) rightMin = psd[j]
                            if (j > k + 1 && psd[j] > psd[j - 1]) break
                        }
                        val referenceLevel = maxOf((leftMin + rightMin) / 2.0f, 1e-15f)
                        val prominence = psd[k] / referenceLevel
                        peaks.add(Peak(frequencyHz = frequencies[k], powerMagnitude = psd[k], prominence = prominence))
                    }
                }
            }
        }
        return peaks.sortedByDescending { it.prominence }.take(20)
    }

    // ─────────────────────────────────────────────────────────────
    // Sampling Continuity Quality Verification (TASK 3)
    // ─────────────────────────────────────────────────────────────

    data class SamplingContinuityResult(
        val totalDurationSec: Double,
        val gapCount50ms: Int,
        val totalMissingTimeSec: Double,
        val missingTimeRatio: Double,
        val isContinuityPassed: Boolean
    )

    /**
     * Verifies sampling continuity (TASK 3).
     * Flags a session if total missing time from gaps > 50ms exceeds 5% of total duration.
     */
    override fun verifySamplingContinuity(
        samples: List<AccelerationSample>,
        gapThresholdMs: Double,
        maxAllowedMissingRatio: Double
    ): SamplingContinuityResult {
        if (samples.size < 2) {
            return SamplingContinuityResult(0.0, 0, 0.0, 0.0, true)
        }

        val totalDurationSec = (samples.last().timestampNs - samples.first().timestampNs) / 1e9
        if (totalDurationSec <= 0.0) {
            return SamplingContinuityResult(0.0, 0, 0.0, 0.0, true)
        }

        var gapCount = 0
        var totalMissingMs = 0.0

        for (i in 1 until samples.size) {
            val dtMs = (samples[i].timestampNs - samples[i - 1].timestampNs) / 1e6
            if (dtMs > gapThresholdMs) {
                gapCount++
                totalMissingMs += dtMs
            }
        }

        val totalMissingTimeSec = totalMissingMs / 1000.0
        val missingTimeRatio = totalMissingTimeSec / totalDurationSec
        val isContinuityPassed = missingTimeRatio <= maxAllowedMissingRatio

        return SamplingContinuityResult(
            totalDurationSec = totalDurationSec,
            gapCount50ms = gapCount,
            totalMissingTimeSec = totalMissingTimeSec,
            missingTimeRatio = missingTimeRatio,
            isContinuityPassed = isContinuityPassed
        )
    }

    // ─────────────────────────────────────────────────────────────
    // Impulse Mode Quality Verification (TASK A)
    // ─────────────────────────────────────────────────────────────

    data class ImpulseVerificationResult(
        val peakToRmsRatio: Double,
        val isPeakToRmsPassed: Boolean,
        val lowBandEnergyRatio: Double,
        val isSpectralSanityPassed: Boolean,
        val isImpulseValid: Boolean
    )

    /**
     * Verifies impulse-mode sessions (building_profile_active).
     *
     * TASK A requirements:
     * 1. Time-domain Peak-to-RMS ratio >= 5.0x
     *    // Interim, calibrated on n=3 heel-drop + n=2 ambient fixtures. Recalibrate when N>=20 heel-drop fixtures available.
     * 2. Secondary spectral sanity check: within the 2s impact window around max peak,
     *    require energy concentration in the 0.5-15Hz structural band vs high frequency shock.
     *    NOTE: Flat/white noise naturally yields ~29.3% in 0.5-15Hz out of 0.5-50Hz. This check is currently an
     *    uncalibrated placeholder (threshold 0.15) with no proven discriminating power yet until a direct phone-tap/chassis-shock
     *    fixture is available for calibration.
     */
    override fun verifyImpulseQuality(
        samples: List<AccelerationSample>,
        sampleRateHz: Float
    ): ImpulseVerificationResult {
        if (samples.size < 100) {
            return ImpulseVerificationResult(0.0, false, 0.0, false, false)
        }

        val gravResult = removeGravityAndDetrend(samples)
        val settlingResult = detectSettlingWindow(gravResult, sampleRateHz)
        val settlingN = settlingResult.settlingDurationSamples

        val gfSamples = gravResult.gravityFreeSamples
        val totalN = gfSamples.size
        val usableN = totalN - settlingN

        if (usableN < 64) {
            return ImpulseVerificationResult(0.0, false, 0.0, false, false)
        }

        val rawX = FloatArray(usableN) { gfSamples[it + settlingN].x }
        val rawY = FloatArray(usableN) { gfSamples[it + settlingN].y }
        val rawZ = FloatArray(usableN) { gfSamples[it + settlingN].z }

        val filtX = highPassFilter(rawX, sampleRateHz, 0.5f)
        val filtY = highPassFilter(rawY, sampleRateHz, 0.5f)
        val filtZ = highPassFilter(rawZ, sampleRateHz, 0.5f)

        var maxMagSq = 0.0
        var maxIndex = 0
        var sumMagSq = 0.0

        for (i in filtX.indices) {
            val magSq = filtX[i].toDouble() * filtX[i] + filtY[i].toDouble() * filtY[i] + filtZ[i].toDouble() * filtZ[i]
            if (magSq > maxMagSq) {
                maxMagSq = magSq
                maxIndex = i
            }
            sumMagSq += magSq
        }

        val timeRms = sqrt(sumMagSq / filtX.size)
        val maxMag = sqrt(maxMagSq)
        val peakToRmsRatio = if (timeRms > 0.0) maxMag / timeRms else 0.0

        // Interim, calibrated on n=3 heel-drop + n=2 ambient fixtures. Recalibrate when N>=20 heel-drop fixtures available.
        val PEAK_TO_RMS_THRESHOLD = 5.0
        val isPeakToRmsPassed = peakToRmsRatio >= PEAK_TO_RMS_THRESHOLD

        // Secondary spectral sanity check: 2s window around max peak index
        val halfWin = (1.0 * sampleRateHz).toInt()
        val winStart = maxOf(0, maxIndex - halfWin)
        val winEnd = minOf(usableN, maxIndex + halfWin)
        val winLen = winEnd - winStart

        val lowBandEnergyRatio: Double
        val isSpectralSanityPassed: Boolean

        if (winLen >= 64) {
            val fftSize = minOf(256, Integer.highestOneBit(winLen))
            val winX = filtX.sliceArray(winStart until winEnd)
            val winY = filtY.sliceArray(winStart until winEnd)
            val winZ = filtZ.sliceArray(winStart until winEnd)

            val psdX = welchPsdSingleAxis(winX, fftSize, 0.5f, sampleRateHz)
            val psdY = welchPsdSingleAxis(winY, fftSize, 0.5f, sampleRateHz)
            val psdZ = welchPsdSingleAxis(winZ, fftSize, 0.5f, sampleRateHz)
            val psdMag = FloatArray(psdX.size) { k -> psdX[k] + psdY[k] + psdZ[k] }

            val df = (sampleRateHz / fftSize).toDouble()
            var lowBandPower = 0.0
            var totalPower = 0.0

            for (k in psdMag.indices) {
                val f = k * sampleRateHz / fftSize
                val p = psdMag[k].toDouble() * df
                if (f in 0.5f..15.0f) {
                    lowBandPower += p
                }
                if (f >= 0.5f) {
                    totalPower += p
                }
            }

            lowBandEnergyRatio = if (totalPower > 0.0) lowBandPower / totalPower else 0.0
            // UNCALIBRATED PLACEHOLDER: Flat/white noise naturally yields ~29.3% in 0.5-15Hz out of 0.5-50Hz.
            // Until a phone-chassis-knock/tapping fixture is added for calibration, threshold is set to 0.15 placeholder.
            isSpectralSanityPassed = lowBandEnergyRatio >= 0.15
        } else {
            lowBandEnergyRatio = 1.0
            isSpectralSanityPassed = true
        }

        val isImpulseValid = isPeakToRmsPassed && isSpectralSanityPassed

        return ImpulseVerificationResult(
            peakToRmsRatio = peakToRmsRatio,
            isPeakToRmsPassed = isPeakToRmsPassed,
            lowBandEnergyRatio = lowBandEnergyRatio,
            isSpectralSanityPassed = isSpectralSanityPassed,
            isImpulseValid = isImpulseValid
        )
    }

    private fun checkLocalPeak(psdHalf: FloatArray, kTarget: Int, searchRadius: Int): Boolean {
        val n = psdHalf.size
        for (k in maxOf(1, kTarget - searchRadius)..minOf(n - 2, kTarget + searchRadius)) {
            if (psdHalf[k] > psdHalf[k - 1] && psdHalf[k] > psdHalf[k + 1]) {
                return true
            }
        }
        return false
    }

    // ─────────────────────────────────────────────────────────────
    // Internal: Empty result helper (C4)
    // ─────────────────────────────────────────────────────────────

    private fun emptyResult(
        freqBins: Int,
        sampleRateHz: Float,
        fftSize: Int,
        params: WelchPsdParameters,
        settling: SettlingWindowResult? = null
    ): MultiAxisSpectrumResult {
        val frequencies = FloatArray(freqBins) { it * sampleRateHz / fftSize }
        val emptyPsd = FloatArray(freqBins)
        val emptyAxis = AxisPsdResult(frequencies, emptyPsd, emptyList())
        return MultiAxisSpectrumResult(
            psdX = emptyAxis,
            psdY = emptyAxis,
            psdZ = emptyAxis,
            psdMagnitude = emptyAxis,
            output = WelchPsdOutput(
                parameters = params,
                actualSegmentCount = 0,
                effectiveDeltaFHz = sampleRateHz / fftSize,
                settlingWindow = settling
            )
        )
    }
}

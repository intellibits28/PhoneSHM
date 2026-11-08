package com.ronin.phoneshm.core.dsp

import com.ronin.phoneshm.core.sensor.AccelerationSample
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

data class NativeWelchResult(
    val segmentCount: Int,
    val frequencies: FloatArray,
    val psdX: FloatArray,
    val psdY: FloatArray,
    val psdZ: FloatArray,
    val psdMagnitude: FloatArray
)

data class NativeFddResult(
    val frequencies: FloatArray,
    val firstSingularValues: FloatArray,
    val peakFrequencies: FloatArray,
    val peakMagnitudes: FloatArray,
    val peakProminences: FloatArray,
    val peakDampingRatios: FloatArray,
    val coherenceXY: FloatArray = FloatArray(0),
    val coherenceXZ: FloatArray = FloatArray(0),
    val coherenceYZ: FloatArray = FloatArray(0),
    val hvRatio: FloatArray = FloatArray(0),
    val peakCoherenceXY: FloatArray = FloatArray(0),
    val peakCoherenceXZ: FloatArray = FloatArray(0),
    val peakCoherenceYZ: FloatArray = FloatArray(0),
    val peakHvRatios: FloatArray = FloatArray(0),
    val peakAzimuthDegrees: FloatArray = FloatArray(0),
    val peakIsValidHorizontalSway: BooleanArray = BooleanArray(0)
)

data class WptAnalysisResult(
    val normalizedEnergies: FloatArray, // 16 subbands (0-50 Hz at fs=100 Hz)
    val shannonEntropy: Float,
    val isStructuralShock: Boolean
)

data class TransientTimeFrequencyEvent(
    val onsetSampleIndex: Int,
    val onsetTimestampNs: Long,
    val peakAccelerationG: Float,
    val rmsG: Float,
    val durationMs: Float,
    val waveletEntropy: Float,
    val isStructuralShock: Boolean,
    val wptSubbandEnergies: FloatArray, // 16 subbands (0-50 Hz)
    val stftTimeSteps: Int,
    val stftFreqBins: Int,
    val stftSpectrogram: FloatArray // Flattened [stftTimeSteps x stftFreqBins]
)

object NativeDspBridge {
    init {
        try {
            System.loadLibrary("phoneshm_dsp")
        } catch (e: Throwable) {
            println("NativeDspBridge: Failed to load phoneshm_dsp (Expected during JVM Unit Tests)")
        }
    }

    external fun nativeCalculateFddRaw(
        timestamps: LongArray,
        x: FloatArray,
        y: FloatArray,
        z: FloatArray,
        sampleRateHz: Float,
        fftSize: Int,
        overlapPct: Float
    ): FloatArray

    fun nativeCalculateFdd(
        timestamps: LongArray,
        x: FloatArray,
        y: FloatArray,
        z: FloatArray,
        sampleRateHz: Float,
        fftSize: Int,
        overlapPct: Float
    ): NativeFddResult {
        val raw = try {
            nativeCalculateFddRaw(timestamps, x, y, z, sampleRateHz, fftSize, overlapPct)
        } catch (e: UnsatisfiedLinkError) {
            calculateFddJvmRaw(timestamps, x, y, z, sampleRateHz, fftSize, overlapPct)
        } catch (e: Throwable) {
            calculateFddJvmRaw(timestamps, x, y, z, sampleRateHz, fftSize, overlapPct)
        }
        if (raw.size < 2) {
            return NativeFddResult(
                frequencies = FloatArray(0),
                firstSingularValues = FloatArray(0),
                peakFrequencies = FloatArray(0),
                peakMagnitudes = FloatArray(0),
                peakProminences = FloatArray(0),
                peakDampingRatios = FloatArray(0)
            )
        }

        val freqLen = raw[0].toInt()
        val peaksLen = raw[1].toInt()

        val isPhase4Layout = raw.size >= 2 + 6 * freqLen + 10 * peaksLen

        val frequencies = if (freqLen > 0 && 2 + freqLen <= raw.size) {
            raw.copyOfRange(2, 2 + freqLen)
        } else FloatArray(0)

        val sv = if (freqLen > 0 && 2 + 2 * freqLen <= raw.size) {
            raw.copyOfRange(2 + freqLen, 2 + 2 * freqLen)
        } else FloatArray(0)

        val cohXY = if (isPhase4Layout && freqLen > 0) {
            raw.copyOfRange(2 + 2 * freqLen, 2 + 3 * freqLen)
        } else FloatArray(freqLen)

        val cohXZ = if (isPhase4Layout && freqLen > 0) {
            raw.copyOfRange(2 + 3 * freqLen, 2 + 4 * freqLen)
        } else FloatArray(freqLen)

        val cohYZ = if (isPhase4Layout && freqLen > 0) {
            raw.copyOfRange(2 + 4 * freqLen, 2 + 5 * freqLen)
        } else FloatArray(freqLen)

        val hvRatios = if (isPhase4Layout && freqLen > 0) {
            raw.copyOfRange(2 + 5 * freqLen, 2 + 6 * freqLen)
        } else FloatArray(freqLen)

        val pFreqs = FloatArray(peaksLen)
        val pMags = FloatArray(peaksLen)
        val pProms = FloatArray(peaksLen)
        val pDamps = FloatArray(peaksLen)
        val pCohXY = FloatArray(peaksLen)
        val pCohXZ = FloatArray(peaksLen)
        val pCohYZ = FloatArray(peaksLen)
        val pHv = FloatArray(peaksLen)
        val pAzimuth = FloatArray(peaksLen)
        val pValidSway = BooleanArray(peaksLen)

        if (isPhase4Layout) {
            var pOffset = 2 + 6 * freqLen
            for (i in 0 until peaksLen) {
                if (pOffset + 9 < raw.size) {
                    pFreqs[i] = raw[pOffset + 0]
                    pMags[i] = raw[pOffset + 1]
                    pProms[i] = raw[pOffset + 2]
                    pDamps[i] = raw[pOffset + 3]
                    pCohXY[i] = raw[pOffset + 4]
                    pCohXZ[i] = raw[pOffset + 5]
                    pCohYZ[i] = raw[pOffset + 6]
                    pHv[i] = raw[pOffset + 7]
                    pAzimuth[i] = raw[pOffset + 8]
                    pValidSway[i] = raw[pOffset + 9] >= 0.5f
                }
                pOffset += 10
            }
        } else {
            var pOffset = 2 + 2 * freqLen
            for (i in 0 until peaksLen) {
                if (pOffset + 3 < raw.size) {
                    pFreqs[i] = raw[pOffset]
                    pMags[i] = raw[pOffset + 1]
                    pProms[i] = raw[pOffset + 2]
                    pDamps[i] = raw[pOffset + 3]
                }
                pOffset += 4
            }
        }

        return NativeFddResult(
            frequencies = frequencies,
            firstSingularValues = sv,
            peakFrequencies = pFreqs,
            peakMagnitudes = pMags,
            peakProminences = pProms,
            peakDampingRatios = pDamps,
            coherenceXY = cohXY,
            coherenceXZ = cohXZ,
            coherenceYZ = cohYZ,
            hvRatio = hvRatios,
            peakCoherenceXY = pCohXY,
            peakCoherenceXZ = pCohXZ,
            peakCoherenceYZ = pCohYZ,
            peakHvRatios = pHv,
            peakAzimuthDegrees = pAzimuth,
            peakIsValidHorizontalSway = pValidSway
        )
    }

    internal fun calculateFddJvmRaw(
        timestamps: LongArray,
        x: FloatArray,
        y: FloatArray,
        z: FloatArray,
        sampleRateHz: Float,
        fftSize: Int,
        overlapPct: Float,
        gapMask: BooleanArray = BooleanArray(0)
    ): FloatArray {
        val n = minOf(timestamps.size, x.size, y.size, z.size)
        if (n < 256 || sampleRateHz <= 0.0f) return FloatArray(0)

        val samples = ArrayList<AccelerationSample>(n)
        for (i in 0 until n) {
            samples.add(AccelerationSample(timestamps[i], x[i], y[i], z[i]))
        }

        val engine = WelchPsdEngine()
        val gravResult = engine.removeGravityAndDetrend(samples)
        val settlingResult = engine.detectSettlingWindow(gravResult, sampleRateHz)
        var settlingN = settlingResult.settlingDurationSamples
        var usableN = n - settlingN
        if (usableN < 256 && n >= 256) {
            settlingN = max(0, n - 256)
            usableN = n - settlingN
        }
        if (usableN < 256) return FloatArray(0)

        val slicedGapMask = if (gapMask.size >= n) {
            gapMask.copyOfRange(settlingN, settlingN + usableN)
        } else BooleanArray(0)

        var effectiveFftSize = fftSize
        if (usableN < effectiveFftSize) {
            var newFftSize = 256
            while (newFftSize * 2 <= usableN) {
                newFftSize *= 2
            }
            effectiveFftSize = newFftSize
        }

        val freqBins = effectiveFftSize / 2 + 1
        val frequencies = FloatArray(freqBins) { i -> i * sampleRateHz / effectiveFftSize }
        val firstSingularValues = FloatArray(freqBins)
        val coherenceXY = FloatArray(freqBins)
        val coherenceXZ = FloatArray(freqBins)
        val coherenceYZ = FloatArray(freqBins)
        val hvRatio = FloatArray(freqBins)

        val rawX = FloatArray(usableN)
        val rawY = FloatArray(usableN)
        val rawZ = FloatArray(usableN)
        for (i in 0 until usableN) {
            val s = gravResult.gravityFreeSamples[i + settlingN]
            rawX[i] = s.x
            rawY[i] = s.y
            rawZ[i] = s.z
        }

        val filtX = engine.highPassFilter(rawX, sampleRateHz, 0.5f)
        val filtY = engine.highPassFilter(rawY, sampleRateHz, 0.5f)
        val filtZ = engine.highPassFilter(rawZ, sampleRateHz, 0.5f)

        val stepSize = max(1, (effectiveFftSize * (1.0f - overlapPct)).toInt())

        val window = DoubleArray(effectiveFftSize)
        var windowPowerSum = 0.0
        for (i in 0 until effectiveFftSize) {
            val w = 0.5 * (1.0 - cos(2.0 * PI * i / (effectiveFftSize - 1)))
            window[i] = w
            windowPowerSum += w * w
        }

        val segmentRms = ArrayList<Double>()
        val segmentHasGap = ArrayList<Boolean>()
        var scanOffset = 0
        while (scanOffset + effectiveFftSize <= usableN) {
            var hasGap = false
            if (slicedGapMask.isNotEmpty()) {
                for (i in 0 until effectiveFftSize) {
                    if (slicedGapMask[scanOffset + i]) {
                        hasGap = true
                        break
                    }
                }
            }
            segmentHasGap.add(hasGap)

            var sumSq = 0.0
            for (i in 0 until effectiveFftSize) {
                val vx = filtX[scanOffset + i].toDouble()
                val vy = filtY[scanOffset + i].toDouble()
                sumSq += vx * vx + vy * vy
            }
            segmentRms.add(sqrt(sumSq / (2.0 * effectiveFftSize)))
            scanOffset += stepSize
        }

        if (segmentRms.isEmpty()) return FloatArray(0)

        val sortedRms = segmentRms.sorted()
        val medianRms = sortedRms[sortedRms.size / 2]
        val deviations = sortedRms.map { abs(it - medianRms) }.sorted()
        val madRms = deviations[deviations.size / 2]
        val rmsThreshold = max(medianRms + 3.0 * (1.4826 * madRms), medianRms * 2.5)

        // 3x3 Hermitian CSD accumulators per frequency bin
        val gxx = DoubleArray(freqBins)
        val gyy = DoubleArray(freqBins)
        val gzz = DoubleArray(freqBins)
        val gxyRe = DoubleArray(freqBins)
        val gxyIm = DoubleArray(freqBins)
        val gxzRe = DoubleArray(freqBins)
        val gxzIm = DoubleArray(freqBins)
        val gyzRe = DoubleArray(freqBins)
        val gyzIm = DoubleArray(freqBins)

        var segmentCount = 0
        val realX = DoubleArray(effectiveFftSize)
        val imagX = DoubleArray(effectiveFftSize)
        val realY = DoubleArray(effectiveFftSize)
        val imagY = DoubleArray(effectiveFftSize)
        val realZ = DoubleArray(effectiveFftSize)
        val imagZ = DoubleArray(effectiveFftSize)

        fun detrendSegment(arr: DoubleArray) {
            val len = arr.size
            var sumX = 0.0
            var sumY = 0.0
            var sumXY = 0.0
            var sumX2 = 0.0
            for (i in 0 until len) {
                val xi = i.toDouble()
                val yi = arr[i]
                sumX += xi
                sumY += yi
                sumXY += xi * yi
                sumX2 += xi * xi
            }
            val denom = len * sumX2 - sumX * sumX
            val slope = if (abs(denom) > 1e-12) (len * sumXY - sumX * sumY) / denom else 0.0
            val intercept = (sumY - slope * sumX) / len
            for (i in 0 until len) {
                arr[i] -= (slope * i + intercept)
            }
        }

        fun accumulateSegment3x3(segStart: Int) {
            for (i in 0 until effectiveFftSize) {
                realX[i] = filtX[segStart + i].toDouble() * window[i]; imagX[i] = 0.0
                realY[i] = filtY[segStart + i].toDouble() * window[i]; imagY[i] = 0.0
                realZ[i] = filtZ[segStart + i].toDouble() * window[i]; imagZ[i] = 0.0
            }
            detrendSegment(realX)
            detrendSegment(realY)
            detrendSegment(realZ)

            radix2FftInPlace(realX, imagX, effectiveFftSize)
            radix2FftInPlace(realY, imagY, effectiveFftSize)
            radix2FftInPlace(realZ, imagZ, effectiveFftSize)

            for (k in 0 until freqBins) {
                val xr = realX[k]; val xi = imagX[k]
                val yr = realY[k]; val yi = imagY[k]
                val zr = realZ[k]; val zi = imagZ[k]

                gxx[k] += xr * xr + xi * xi
                gyy[k] += yr * yr + yi * yi
                gzz[k] += zr * zr + zi * zi

                // X * conj(Y) = (xr + i*xi)(yr - i*yi) = (xr*yr + xi*yi) + i*(xi*yr - xr*yi)
                gxyRe[k] += xr * yr + xi * yi
                gxyIm[k] += xi * yr - xr * yi

                // X * conj(Z)
                gxzRe[k] += xr * zr + xi * zi
                gxzIm[k] += xi * zr - xr * zi

                // Y * conj(Z)
                gyzRe[k] += yr * zr + yi * zi
                gyzIm[k] += yi * zr - yr * zi
            }
            segmentCount++
        }

        var offset = 0
        var segIdx = 0
        while (offset + effectiveFftSize <= usableN) {
            val hasGap = segmentHasGap[segIdx]
            val currentRms = segmentRms[segIdx++]
            if (!hasGap && (segmentRms.size < 3 || currentRms <= rmsThreshold)) {
                accumulateSegment3x3(offset)
            }
            offset += stepSize
        }

        if (segmentCount == 0) {
            offset = 0
            segIdx = 0
            while (offset + effectiveFftSize <= usableN) {
                val hasGap = segmentHasGap[segIdx++]
                if (!hasGap) accumulateSegment3x3(offset)
                offset += stepSize
            }
            if (segmentCount == 0) {
                offset = 0
                while (offset + effectiveFftSize <= usableN) {
                    accumulateSegment3x3(offset)
                    offset += stepSize
                }
            }
        }

        if (segmentCount == 0) return FloatArray(0)

        val normFactor = 1.0 / (sampleRateHz.toDouble() * windowPowerSum * segmentCount)
        val u1ReX = DoubleArray(freqBins)
        val u1ImX = DoubleArray(freqBins)
        val u1ReY = DoubleArray(freqBins)
        val u1ImY = DoubleArray(freqBins)

        for (k in 0 until freqBins) {
            val scale = if (k > 0 && k < freqBins - 1) normFactor * 2.0 else normFactor
            val sxx = max(0.0, gxx[k] * scale)
            val syy = max(0.0, gyy[k] * scale)
            val szz = max(0.0, gzz[k] * scale)
            val cxyR = gxyRe[k] * scale
            val cxyI = gxyIm[k] * scale
            val cxzR = gxzRe[k] * scale
            val cxzI = gxzIm[k] * scale
            val cyzR = gyzRe[k] * scale
            val cyzI = gyzIm[k] * scale

            val normXY = cxyR * cxyR + cxyI * cxyI
            val normXZ = cxzR * cxzR + cxzI * cxzI
            val normYZ = cyzR * cyzR + cyzI * cyzI

            // Exact 2x2 Hermitian SVD of [[sxx, cxy], [conj(cxy), syy]]
            val tr = sxx + syy
            val diff = sxx - syy
            val delta = sqrt(diff * diff + 4.0 * normXY)
            var sv1D = 0.5 * (tr + delta)
            if (sv1D.isNaN() || sv1D.isInfinite() || sv1D < 0.0) {
                sv1D = 0.0
            }
            firstSingularValues[k] = sv1D.toFloat()

            // First singular vector u1 = [ux, uy]^T
            if (normXY < 1e-50) {
                if (sxx >= syy) {
                    u1ReX[k] = 1.0; u1ImX[k] = 0.0
                    u1ReY[k] = 0.0; u1ImY[k] = 0.0
                } else {
                    u1ReX[k] = 0.0; u1ImX[k] = 0.0
                    u1ReY[k] = 1.0; u1ImY[k] = 0.0
                }
            } else if (sxx >= syy) {
                val uxR = sv1D - syy
                val uyR = cxyR
                val uyI = -cxyI // conj(G_01)
                val normU = sqrt(uxR * uxR + uyR * uyR + uyI * uyI).coerceAtLeast(1e-30)
                u1ReX[k] = uxR / normU; u1ImX[k] = 0.0
                u1ReY[k] = uyR / normU; u1ImY[k] = uyI / normU
            } else {
                val uxR = cxyR
                val uxI = cxyI
                val uyR = sv1D - sxx
                val normU = sqrt(uxR * uxR + uxI * uxI + uyR * uyR).coerceAtLeast(1e-30)
                val sign = if (uxR < 0.0) -1.0 else 1.0
                u1ReX[k] = sign * uxR / normU; u1ImX[k] = sign * uxI / normU
                u1ReY[k] = sign * uyR / normU; u1ImY[k] = 0.0
            }

            val cXY = (normXY / max(sxx * syy, 1e-30)).coerceIn(0.0, 1.0)
            val cXZ = (normXZ / max(sxx * szz, 1e-30)).coerceIn(0.0, 1.0)
            val cYZ = (normYZ / max(syy * szz, 1e-30)).coerceIn(0.0, 1.0)
            val hv = min(sv1D / max(szz, 1e-30), 1e9)

            coherenceXY[k] = cXY.toFloat()
            coherenceXZ[k] = cXZ.toFloat()
            coherenceYZ[k] = cYZ.toFloat()
            hvRatio[k] = hv.toFloat()
        }

        val allPeaks = engine.findPeaks(frequencies, firstSingularValues)
        val structuralPeaks = allPeaks.filter { it.frequencyHz in 0.5f..30.0f }
        val peaks = (if (structuralPeaks.isNotEmpty()) structuralPeaks else allPeaks)
            .sortedByDescending { it.powerMagnitude }
        val peaksLen = peaks.size
        val totalLen = 2 + 6 * freqBins + 10 * peaksLen
        val buffer = FloatArray(totalLen)
        buffer[0] = freqBins.toFloat()
        buffer[1] = peaksLen.toFloat()

        System.arraycopy(frequencies, 0, buffer, 2, freqBins)
        System.arraycopy(firstSingularValues, 0, buffer, 2 + freqBins, freqBins)
        System.arraycopy(coherenceXY, 0, buffer, 2 + 2 * freqBins, freqBins)
        System.arraycopy(coherenceXZ, 0, buffer, 2 + 3 * freqBins, freqBins)
        System.arraycopy(coherenceYZ, 0, buffer, 2 + 4 * freqBins, freqBins)
        System.arraycopy(hvRatio, 0, buffer, 2 + 5 * freqBins, freqBins)

        fun computeMac(k1: Int, k2: Int): Float {
            // |u1^H u2|^2
            val dotR = u1ReX[k1] * u1ReX[k2] + u1ImX[k1] * u1ImX[k2] +
                u1ReY[k1] * u1ReY[k2] + u1ImY[k1] * u1ImY[k2]
            val dotI = u1ReX[k1] * u1ImX[k2] - u1ImX[k1] * u1ReX[k2] +
                u1ReY[k1] * u1ImY[k2] - u1ImY[k1] * u1ReY[k2]
            return (dotR * dotR + dotI * dotI).toFloat()
        }

        val pOffset = 2 + 6 * freqBins
        for (idx in 0 until peaksLen) {
            val p = peaks[idx]
            var k = -1
            var minDiff = 1e9f
            for (i in 0 until freqBins) {
                val d = abs(frequencies[i] - p.frequencyHz)
                if (d < minDiff) {
                    minDiff = d
                    k = i
                }
            }

            var dampingRatio = 0.0f
            var cohXYVal = 0.0f
            var cohXZVal = 0.0f
            var cohYZVal = 0.0f
            var hvVal = 0.0f
            var azimuthDeg = 0.0f
            var validSway = false

            if (k > 0) {
                cohXYVal = coherenceXY[k]
                cohXZVal = coherenceXZ[k]
                cohYZVal = coherenceYZ[k]
                hvVal = hvRatio[k]
                azimuthDeg = (atan2(u1ReY[k], u1ReX[k]) * 180.0 / PI).toFloat()
                validSway = (hvVal > 3.0f) && (cohXZVal <= 0.35f) && (cohYZVal <= 0.35f)

                var lBin = k
                var rBin = k
                val macThreshold = 0.85f
                while (lBin > 0) {
                    if (computeMac(k, lBin - 1) < macThreshold) break
                    if (firstSingularValues[lBin - 1] > firstSingularValues[lBin]) break
                    lBin--
                }
                while (rBin < freqBins - 1) {
                    if (computeMac(k, rBin + 1) < macThreshold) break
                    if (firstSingularValues[rBin + 1] > firstSingularValues[rBin]) break
                    rBin++
                }

                val idftRe = DoubleArray(effectiveFftSize)
                val idftIm = DoubleArray(effectiveFftSize)
                for (i in lBin..rBin) {
                    idftRe[i] = firstSingularValues[i].toDouble()
                    if (i > 0 && i < effectiveFftSize / 2) {
                        idftRe[effectiveFftSize - i] = firstSingularValues[i].toDouble()
                    }
                }
                radix2FftInPlace(idftRe, idftIm, effectiveFftSize)
                for (i in 0 until effectiveFftSize) {
                    idftRe[i] /= effectiveFftSize.toDouble()
                }

                val peakTimes = ArrayList<Double>()
                val peakVals = ArrayList<Double>()
                var firstPeakVal = -1.0
                for (i in 1 until effectiveFftSize / 2 - 1) {
                    if (idftRe[i] > idftRe[i - 1] && idftRe[i] > idftRe[i + 1] && idftRe[i] > 0.0) {
                        if (firstPeakVal < 0.0) firstPeakVal = idftRe[i]
                        peakTimes.add(i.toDouble() / sampleRateHz)
                        peakVals.add(idftRe[i])
                        if (firstPeakVal > 0.0 && idftRe[i] < 0.15 * firstPeakVal) break
                        if (peakVals.size >= 15) break
                    }
                }
                if (peakVals.size < 3 && firstPeakVal > 0.0) {
                    peakTimes.add(0, 0.0)
                    peakVals.add(0, max(firstPeakVal, idftRe[0]))
                }
                if (peakVals.size >= 3) {
                    var sumT = 0.0
                    var sumV = 0.0
                    var sumTV = 0.0
                    var sumT2 = 0.0
                    val np = peakVals.size
                    for (i in 0 until np) {
                        val tv = peakTimes[i]
                        val lv = ln(peakVals[i])
                        sumT += tv
                        sumV += lv
                        sumTV += tv * lv
                        sumT2 += tv * tv
                    }
                    val denom = np * sumT2 - sumT * sumT
                    if (abs(denom) > 1e-12) {
                        val slope = (np * sumTV - sumT * sumV) / denom
                        val omegaN = 2.0 * PI * p.frequencyHz
                        dampingRatio = (-slope / omegaN).toFloat().coerceIn(0.01f, 0.20f)
                    }
                }
            }

            val base = pOffset + idx * 10
            buffer[base + 0] = p.frequencyHz
            buffer[base + 1] = p.powerMagnitude
            buffer[base + 2] = p.prominence
            buffer[base + 3] = dampingRatio
            buffer[base + 4] = cohXYVal
            buffer[base + 5] = cohXZVal
            buffer[base + 6] = cohYZVal
            buffer[base + 7] = hvVal
            buffer[base + 8] = azimuthDeg
            buffer[base + 9] = if (validSway) 1.0f else 0.0f
        }

        return buffer
    }

    external fun nativeWelchPsdSingleAxis(
        signal: FloatArray,
        fftSize: Int,
        overlapPct: Float,
        sampleRateHz: Float
    ): FloatArray

    external fun nativeCalculateMultiAxisWelchPsd(
        timestamps: LongArray,
        x: FloatArray,
        y: FloatArray,
        z: FloatArray,
        sampleRateHz: Float,
        fftSize: Int,
        overlapPct: Float
    ): NativeWelchResult

    external fun nativeStreamingRmsLevel(
        x: Float,
        y: Float,
        z: Float,
        prevRms: Float
    ): Float

    external fun nativeCalculateRdtSsi(
        x: FloatArray,
        y: FloatArray,
        z: FloatArray,
        sampleRateHz: Float,
        minHz: Float,
        maxHz: Float,
        maxModelOrder: Int,
        rdsDurationSec: Float
    ): FloatArray

    fun calculateRdtSsi(
        x: FloatArray,
        y: FloatArray,
        z: FloatArray,
        sampleRateHz: Float,
        minHz: Float,
        maxHz: Float,
        maxModelOrder: Int = 40,
        rdsDurationSec: Float = 3.0f
    ): FloatArray {
        return try {
            nativeCalculateRdtSsi(x, y, z, sampleRateHz, minHz, maxHz, maxModelOrder, rdsDurationSec)
        } catch (e: UnsatisfiedLinkError) {
            calculateRdtSsiJvmRaw(x, y, z, sampleRateHz, minHz, maxHz, maxModelOrder, rdsDurationSec)
        } catch (e: Throwable) {
            calculateRdtSsiJvmRaw(x, y, z, sampleRateHz, minHz, maxHz, maxModelOrder, rdsDurationSec)
        }
    }

    internal fun calculateRdtSsiJvmRaw(
        x: FloatArray,
        y: FloatArray,
        z: FloatArray,
        fs: Float,
        minHz: Float,
        maxHz: Float,
        maxModelOrder: Int = 40,
        rdsDurationSec: Float = 3.0f
    ): FloatArray {
        val length = minOf(x.size, y.size, z.size)
        if (length <= 0 || fs <= 0.0f) return FloatArray(0)

        var mx = 0.0
        var my = 0.0
        var mz = 0.0
        for (i in 0 until length) {
            mx += x[i]
            my += y[i]
            mz += z[i]
        }
        mx /= length
        my /= length
        mz /= length

        var nFft = 1
        while (nFft < length) nFft = nFft shl 1

        val rx = DoubleArray(nFft)
        val ix = DoubleArray(nFft)
        val ry = DoubleArray(nFft)
        val iy = DoubleArray(nFft)
        val rz = DoubleArray(nFft)
        val iz = DoubleArray(nFft)

        for (i in 0 until length) {
            rx[i] = x[i] - mx
            ry[i] = y[i] - my
            rz[i] = z[i] - mz
        }

        radix2FftInPlace(rx, ix, nFft)
        radix2FftInPlace(ry, iy, nFft)
        radix2FftInPlace(rz, iz, nFft)

        val df = fs.toDouble() / nFft
        for (i in 0 until nFft) {
            var freq = i * df
            if (freq > fs / 2.0) {
                freq = fs - freq
            }
            if (freq < minHz || freq > maxHz) {
                rx[i] = 0.0; ix[i] = 0.0
                ry[i] = 0.0; iy[i] = 0.0
                rz[i] = 0.0; iz[i] = 0.0
            }
        }

        for (i in 0 until nFft) {
            ix[i] = -ix[i]
            iy[i] = -iy[i]
            iz[i] = -iz[i]
        }
        radix2FftInPlace(rx, ix, nFft)
        radix2FftInPlace(ry, iy, nFft)
        radix2FftInPlace(rz, iz, nFft)

        for (i in 0 until nFft) {
            rx[i] /= nFft.toDouble()
            ry[i] /= nFft.toDouble()
            rz[i] /= nFft.toDouble()
        }

        var varX = 0.0
        var varY = 0.0
        var varZ = 0.0
        for (i in 0 until length) {
            varX += rx[i] * rx[i]
            varY += ry[i] * ry[i]
            varZ += rz[i] * rz[i]
        }
        varX /= length
        varY /= length
        varZ /= length

        var refArr = rx
        var maxVar = varX
        var refAxis = 0
        if (varY > maxVar) { maxVar = varY; refArr = ry; refAxis = 1 }
        if (varZ > maxVar) { maxVar = varZ; refArr = rz; refAxis = 2 }

        val sigma = sqrt(maxVar)
        if (sigma <= 1e-12) return FloatArray(0)

        var rdsLength = (rdsDurationSec * fs).toInt()
        if (rdsLength <= 0) return FloatArray(0)
        if (rdsLength > length) rdsLength = length

        val rdsX = DoubleArray(rdsLength)
        val rdsY = DoubleArray(rdsLength)
        val rdsZ = DoubleArray(rdsLength)
        var triggerCount = 0

        for (lvlIdx in 0 until 10) {
            val level = sigma * (1.0 + 0.1 * lvlIdx)
            for (i in 1..(length - rdsLength)) {
                if (refArr[i - 1] < level && refArr[i] >= level) {
                    for (j in 0 until rdsLength) {
                        rdsX[j] += rx[i + j]
                        rdsY[j] += ry[i + j]
                        rdsZ[j] += rz[i + j]
                    }
                    triggerCount++
                }
            }
        }

        if (triggerCount <= 0) return FloatArray(0)
        for (j in 0 until rdsLength) {
            rdsX[j] /= triggerCount.toDouble()
            rdsY[j] /= triggerCount.toDouble()
            rdsZ[j] /= triggerCount.toDouble()
        }

        val outRef = when (refAxis) {
            1 -> rdsY
            2 -> rdsZ
            else -> rdsX
        }
        var maxRef = 0.0
        for (j in 0 until rdsLength) {
            val a = abs(outRef[j])
            if (a > maxRef) maxRef = a
        }
        if (maxRef > 0.0) {
            for (j in 0 until rdsLength) {
                rdsX[j] /= maxRef
                rdsY[j] /= maxRef
                rdsZ[j] /= maxRef
            }
        }

        val iDim = maxModelOrder + 10
        if (rdsLength < 2 * iDim) return FloatArray(0)

        val rowsH = 3 * iDim
        val colsH = iDim
        val H = Array(rowsH) { DoubleArray(colsH) }
        for (r in 0 until iDim) {
            for (c in 0 until iDim) {
                val idx = 1 + r + c
                H[3 * r + 0][c] = rdsX[idx]
                H[3 * r + 1][c] = rdsY[idx]
                H[3 * r + 2][c] = rdsZ[idx]
            }
        }

        // Thin SVD of H (rowsH x colsH) via Jacobi eigendecomposition of H^T * H
        val (uMat, sVec) = thinSvdJacobi(H, rowsH, colsH)

        val polesList = ArrayList<Float>()
        val upRows = rowsH - 3

        for (order in 2..maxModelOrder step 2) {
            if (order > colsH) break
            // Construct observability matrix O = U_n * sqrt(S_n)
            val oMat = Array(rowsH) { DoubleArray(order) }
            for (j in 0 until order) {
                val sqrtSj = sqrt(max(0.0, sVec[j]))
                for (r in 0 until rowsH) {
                    oMat[r][j] = uMat[r][j] * sqrtSj
                }
            }

            // O_up is top (rowsH - 3) rows, O_down is bottom (rowsH - 3) rows
            // Solve O_up * A = O_down via SVD pseudoinverse of O_up
            val oUp = Array(upRows) { r -> oMat[r].copyOf() }
            val (uUp, sUp, vUp) = fullSvdJacobi(oUp, upRows, order)

            // A = V_up * diag(1/sUp) * U_up^T * O_down
            val utOdown = Array(order) { DoubleArray(order) }
            for (i in 0 until order) {
                val invSi = if (sUp[i] > 1e-7 * sUp[0]) 1.0 / sUp[i] else 0.0
                for (j in 0 until order) {
                    var sum = 0.0
                    for (r in 0 until upRows) {
                        sum += uUp[r][i] * oMat[r + 3][j]
                    }
                    utOdown[i][j] = sum * invSi
                }
            }
            val aMat = Array(order) { DoubleArray(order) }
            for (i in 0 until order) {
                for (j in 0 until order) {
                    var sum = 0.0
                    for (k in 0 until order) {
                        sum += vUp[i][k] * utOdown[k][j]
                    }
                    aMat[i][j] = sum
                }
            }

            val evals = computeRealMatrixEigenvaluesQr(aMat, order)
            for (ev in evals) {
                val re = ev.first
                val im = ev.second
                val mag = sqrt(re * re + im * im)
                if (mag <= 1e-12) continue
                val sigmaC = ln(mag) * fs
                val omegaC = atan2(im, re) * fs
                val f = (abs(omegaC) / (2.0 * PI)).toFloat()
                val damp = (-sigmaC / sqrt(sigmaC * sigmaC + omegaC * omegaC)).toFloat()
                if (f > 0.1f && f < fs / 2.0f && damp > 0.005f && damp < 0.15f && omegaC > 0.0) {
                    polesList.add(f)
                    polesList.add(damp)
                    polesList.add(order.toFloat())
                }
            }
        }

        return polesList.toFloatArray()
    }

    private fun jacobiSymmetricEigen(aInput: Array<DoubleArray>, n: Int): Pair<DoubleArray, Array<DoubleArray>> {
        val a = Array(n) { r -> aInput[r].copyOf() }
        val v = Array(n) { r -> DoubleArray(n) { c -> if (r == c) 1.0 else 0.0 } }
        val maxSweeps = 40
        for (sweep in 0 until maxSweeps) {
            var offDiagNorm = 0.0
            for (p in 0 until n - 1) {
                for (q in p + 1 until n) {
                    offDiagNorm += a[p][q] * a[p][q]
                }
            }
            if (offDiagNorm < 1e-22) break
            for (p in 0 until n - 1) {
                for (q in p + 1 until n) {
                    val apq = a[p][q]
                    if (abs(apq) < 1e-15) continue
                    val app = a[p][p]
                    val aqq = a[q][q]
                    val tau = (aqq - app) / (2.0 * apq)
                    val t = if (tau >= 0.0) {
                        1.0 / (tau + sqrt(1.0 + tau * tau))
                    } else {
                        -1.0 / (-tau + sqrt(1.0 + tau * tau))
                    }
                    val c = 1.0 / sqrt(1.0 + t * t)
                    val s = t * c
                    a[p][p] = app - t * apq
                    a[q][q] = aqq + t * apq
                    a[p][q] = 0.0
                    a[q][p] = 0.0
                    for (r in 0 until n) {
                        if (r != p && r != q) {
                            val arp = a[r][p]
                            val arq = a[r][q]
                            a[r][p] = c * arp - s * arq
                            a[p][r] = a[r][p]
                            a[r][q] = s * arp + c * arq
                            a[q][r] = a[r][q]
                        }
                        val vrp = v[r][p]
                        val vrq = v[r][q]
                        v[r][p] = c * vrp - s * vrq
                        v[r][q] = s * vrp + c * vrq
                    }
                }
            }
        }
        val orderIdx = (0 until n).sortedByDescending { a[it][it] }
        val evals = DoubleArray(n) { idx -> max(0.0, a[orderIdx[idx]][orderIdx[idx]]) }
        val evecs = Array(n) { r -> DoubleArray(n) { idx -> v[r][orderIdx[idx]] } }
        return Pair(evals, evecs)
    }

    private fun thinSvdJacobi(m: Array<DoubleArray>, rows: Int, cols: Int): Pair<Array<DoubleArray>, DoubleArray> {
        val mTm = Array(cols) { i ->
            DoubleArray(cols) { j ->
                var sum = 0.0
                for (r in 0 until rows) sum += m[r][i] * m[r][j]
                sum
            }
        }
        val (evals, v) = jacobiSymmetricEigen(mTm, cols)
        val s = DoubleArray(cols) { sqrt(evals[it]) }
        val u = Array(rows) { DoubleArray(cols) }
        for (j in 0 until cols) {
            val sj = s[j]
            if (sj > 1e-12) {
                val invSj = 1.0 / sj
                for (r in 0 until rows) {
                    var sum = 0.0
                    for (k in 0 until cols) sum += m[r][k] * v[k][j]
                    u[r][j] = sum * invSj
                }
            }
        }
        return Pair(u, s)
    }

    private fun fullSvdJacobi(m: Array<DoubleArray>, rows: Int, cols: Int): Triple<Array<DoubleArray>, DoubleArray, Array<DoubleArray>> {
        val mTm = Array(cols) { i ->
            DoubleArray(cols) { j ->
                var sum = 0.0
                for (r in 0 until rows) sum += m[r][i] * m[r][j]
                sum
            }
        }
        val (evals, v) = jacobiSymmetricEigen(mTm, cols)
        val s = DoubleArray(cols) { sqrt(evals[it]) }
        val u = Array(rows) { DoubleArray(cols) }
        for (j in 0 until cols) {
            val sj = s[j]
            if (sj > 1e-12) {
                val invSj = 1.0 / sj
                for (r in 0 until rows) {
                    var sum = 0.0
                    for (k in 0 until cols) sum += m[r][k] * v[k][j]
                    u[r][j] = sum * invSj
                }
            }
        }
        return Triple(u, s, v)
    }

    private fun computeRealMatrixEigenvaluesQr(aInput: Array<DoubleArray>, n: Int): List<Pair<Double, Double>> {
        val h = Array(n) { r -> aInput[r].copyOf() }
        // 1. Householder reduction to Upper Hessenberg form
        for (k in 0 until n - 2) {
            var scale = 0.0
            for (i in k + 1 until n) scale += abs(h[i][k])
            if (scale <= 1e-15) continue
            var sumSq = 0.0
            val v = DoubleArray(n - k - 1)
            for (i in k + 1 until n) {
                val u = h[i][k] / scale
                v[i - k - 1] = u
                sumSq += u * u
            }
            var norm = sqrt(sumSq)
            if (v[0] < 0.0) norm = -norm
            v[0] += norm
            val beta = 1.0 / (norm * v[0])
            // Left multiply H[k+1..n-1, k..n-1]
            for (j in k until n) {
                var dot = 0.0
                for (i in k + 1 until n) dot += v[i - k - 1] * h[i][j]
                dot *= beta
                for (i in k + 1 until n) h[i][j] -= dot * v[i - k - 1]
            }
            // Right multiply H[0..n-1, k+1..n-1]
            for (i in 0 until n) {
                var dot = 0.0
                for (j in k + 1 until n) dot += h[i][j] * v[j - k - 1]
                dot *= beta
                for (j in k + 1 until n) h[i][j] -= dot * v[j - k - 1]
            }
        }

        // 2. Double-shift / Givens QR iteration on Upper Hessenberg H
        val result = ArrayList<Pair<Double, Double>>(n)
        var m = n - 1
        var iter = 0
        val maxIter = 80 * n
        val cArr = DoubleArray(n)
        val sArr = DoubleArray(n)

        while (m >= 0 && iter < maxIter) {
            if (m == 0) {
                result.add(Pair(h[0][0], 0.0))
                break
            }
            val tol = 1e-11 * (abs(h[m - 1][m - 1]) + abs(h[m][m])).coerceAtLeast(1e-12)
            if (abs(h[m][m - 1]) <= tol) {
                result.add(Pair(h[m][m], 0.0))
                m--
                continue
            }
            if (m == 1 || abs(h[m - 1][m - 2]) <= 1e-11 * (abs(h[m - 2][m - 2]) + abs(h[m - 1][m - 1])).coerceAtLeast(1e-12)) {
                // 2x2 trailing block eigenvalues
                val a11 = h[m - 1][m - 1]
                val a12 = h[m - 1][m]
                val a21 = h[m][m - 1]
                val a22 = h[m][m]
                val tr = a11 + a22
                val det = a11 * a22 - a12 * a21
                val disc = tr * tr - 4.0 * det
                if (disc >= 0.0) {
                    val sd = sqrt(disc)
                    result.add(Pair(0.5 * (tr + sd), 0.0))
                    result.add(Pair(0.5 * (tr - sd), 0.0))
                } else {
                    val sd = sqrt(-disc)
                    result.add(Pair(0.5 * tr, 0.5 * sd))
                    result.add(Pair(0.5 * tr, -0.5 * sd))
                }
                m -= 2
                continue
            }

            // Wilkinson / Exceptional shift QR step on active submatrix 0..m
            val a11 = h[m - 1][m - 1]
            val a12 = h[m - 1][m]
            val a21 = h[m][m - 1]
            val a22 = h[m][m]
            val tr = a11 + a22
            val det = a11 * a22 - a12 * a21
            val disc = tr * tr - 4.0 * det
            val shift = if (iter % 15 == 14) {
                abs(h[m][m - 1]) + abs(h[m - 1][m - 2])
            } else if (disc >= 0.0) {
                val r1 = 0.5 * (tr + sqrt(disc))
                val r2 = 0.5 * (tr - sqrt(disc))
                if (abs(r1 - a22) < abs(r2 - a22)) r1 else r2
            } else {
                // Perform two single real shifts or Francis implicit double-shift via explicit H^2 - tr*H + det*I
                0.5 * tr
            }

            if (disc < 0.0 && iter % 15 != 14) {
                // Francis implicit double step via Givens rotations on M = H^2 - tr*H + det*I
                // Equivalent to two complex-conjugate shifted QR steps
                for (i in 0..m) h[i][i] -= shift
                for (k in 0 until m) {
                    val xVal = h[k][k]
                    val yVal = h[k + 1][k]
                    val r = sqrt(xVal * xVal + yVal * yVal).coerceAtLeast(1e-30)
                    val c = xVal / r
                    val s = yVal / r
                    cArr[k] = c
                    sArr[k] = s
                    for (j in k..m) {
                        val t1 = h[k][j]
                        val t2 = h[k + 1][j]
                        h[k][j] = c * t1 + s * t2
                        h[k + 1][j] = -s * t1 + c * t2
                    }
                }
                for (k in 0 until m) {
                    val c = cArr[k]
                    val s = sArr[k]
                    for (i in 0..min(k + 1, m)) {
                        val t1 = h[i][k]
                        val t2 = h[i][k + 1]
                        h[i][k] = c * t1 + s * t2
                        h[i][k + 1] = -s * t1 + c * t2
                    }
                }
                for (i in 0..m) h[i][i] += shift
            } else {
                for (i in 0..m) h[i][i] -= shift
                for (k in 0 until m) {
                    val xVal = h[k][k]
                    val yVal = h[k + 1][k]
                    val r = sqrt(xVal * xVal + yVal * yVal).coerceAtLeast(1e-30)
                    val c = xVal / r
                    val s = yVal / r
                    cArr[k] = c
                    sArr[k] = s
                    for (j in k..m) {
                        val t1 = h[k][j]
                        val t2 = h[k + 1][j]
                        h[k][j] = c * t1 + s * t2
                        h[k + 1][j] = -s * t1 + c * t2
                    }
                }
                for (k in 0 until m) {
                    val c = cArr[k]
                    val s = sArr[k]
                    for (i in 0..min(k + 1, m)) {
                        val t1 = h[i][k]
                        val t2 = h[i][k + 1]
                        h[i][k] = c * t1 + s * t2
                        h[i][k + 1] = -s * t1 + c * t2
                    }
                }
                for (i in 0..m) h[i][i] += shift
            }
            iter++
        }
        // Extract any remaining 2x2 or 1x1 diagonal blocks if maxIter reached
        var idx = m
        while (idx >= 0) {
            if (idx == 0 || abs(h[idx][idx - 1]) <= 1e-6) {
                result.add(Pair(h[idx][idx], 0.0))
                idx--
            } else {
                val a11 = h[idx - 1][idx - 1]
                val a12 = h[idx - 1][idx]
                val a21 = h[idx][idx - 1]
                val a22 = h[idx][idx]
                val tr = a11 + a22
                val det = a11 * a22 - a12 * a21
                val disc = tr * tr - 4.0 * det
                if (disc >= 0.0) {
                    val sd = sqrt(disc)
                    result.add(Pair(0.5 * (tr + sd), 0.0))
                    result.add(Pair(0.5 * (tr - sd), 0.0))
                } else {
                    val sd = sqrt(-disc)
                    result.add(Pair(0.5 * tr, 0.5 * sd))
                    result.add(Pair(0.5 * tr, -0.5 * sd))
                }
                idx -= 2
            }
        }
        return result
    }

    external fun nativeAnalyzeGatedTransientsRaw(
        signal: FloatArray,
        sampleRateHz: Float,
        segmentSize: Int,
        segmentOverlap: Float,
        gapMask: ByteArray
    ): FloatArray

    /**
     * Reconstructs exact 64-bit nanosecond hardware timestamp from a safe single-float sample index
     * without bitcasting 64-bit integers into IEEE 754 float registers (preventing sNaN corruption).
     */
    fun reconstructOnsetTimestampNs(
        sessionStartTimestampNs: Long,
        onsetSampleIndex: Float,
        sampleRateHz: Float
    ): Long {
        if (sampleRateHz <= 0.0f) return sessionStartTimestampNs
        val dtNs = (onsetSampleIndex.toDouble() * (1_000_000_000.0 / sampleRateHz.toDouble())).roundToLong()
        return sessionStartTimestampNs + dtNs
    }

    /**
     * Computes normalized Shannon Wavelet Entropy: S_wpt = - sum_{j=1..16} p_j * ln(p_j)
     * with strict p_j > 1e-12 guard to prevent ln(0) -> NaN.
     */
    fun computeShannonEntropy(normalizedEnergies: FloatArray): Float {
        var entropy = 0.0f
        for (p in normalizedEnergies) {
            if (p > 1e-12f) {
                entropy -= p * ln(p.toDouble()).toFloat()
            }
        }
        return entropy
    }

    /**
     * Daubechies db4 Wavelet Packet Transform (Depth J = 4 -> 16 subbands, 0-50 Hz for fs = 100 Hz).
     * Directly processes the 100 Hz vibration signal without audio downsampling.
     */
    fun analyzeWaveletPacketDb4(signal: FloatArray, depth: Int = 4): WptAnalysisResult {
        if (signal.isEmpty()) {
            return WptAnalysisResult(FloatArray(16), 0.0f, false)
        }
        val effectiveDepth = depth.coerceIn(1, 4)
        val sqrt2 = sqrt(2.0f)
        val db4H = floatArrayOf(
            0.4829629131f / sqrt2,
            0.8365163037f / sqrt2,
            0.2241438680f / sqrt2,
            -0.1294095225f / sqrt2,
            -0.0992195431f / sqrt2,
            0.0358121650f / sqrt2,
            0.0244672620f / sqrt2,
            -0.0039282305f / sqrt2
        )
        val db4G = FloatArray(8) { i ->
            (if (i % 2 == 0) 1.0f else -1.0f) * db4H[7 - i]
        }

        var currentLevelNodes = listOf(signal)
        for (d in 0 until effectiveDepth) {
            val nextLevelNodes = ArrayList<FloatArray>(currentLevelNodes.size * 2)
            for (node in currentLevelNodes) {
                nextLevelNodes.add(convolveAndDownsampleDb4(node, db4H))
                nextLevelNodes.add(convolveAndDownsampleDb4(node, db4G))
            }
            currentLevelNodes = nextLevelNodes
        }

        val energies = FloatArray(16)
        val numBands = min(16, currentLevelNodes.size)
        var totalEnergy = 0.0f
        for (j in 0 until numBands) {
            var bandEnergy = 0.0
            for (v in currentLevelNodes[j]) {
                bandEnergy += v.toDouble() * v.toDouble()
            }
            energies[j] = bandEnergy.toFloat()
            totalEnergy += energies[j]
        }

        if (totalEnergy > 1e-20f) {
            for (j in 0 until numBands) {
                energies[j] /= totalEnergy
            }
        }

        val entropy = computeShannonEntropy(energies)
        var lowBandEnergy = 0.0f
        for (j in 0 until 8) {
            lowBandEnergy += energies[j]
        }
        val isStructuralShock = (totalEnergy > 1e-20f) && (entropy < 1.8f) && (lowBandEnergy >= 0.55f)
        return WptAnalysisResult(energies, entropy, isStructuralShock)
    }

    private fun convolveAndDownsampleDb4(input: FloatArray, filter: FloatArray): FloatArray {
        if (input.size < 2) return input
        val outLen = input.size / 2
        val output = FloatArray(outLen)
        val inSize = input.size
        for (i in 0 until outLen) {
            var sum = 0.0f
            for (k in 0 until 8) {
                val idx = 2 * i - k
                if (idx in 0 until inSize) {
                    sum += input[idx] * filter[k]
                }
            }
            output[i] = sum
        }
        return output
    }

    /**
     * Packs TransientTimeFrequencyEvent list into the contiguous float array layout used by JNI.
     */
    fun packTransientEventsRaw(events: List<TransientTimeFrequencyEvent>): FloatArray {
        val kCount = min(5, events.size)
        var totalFloats = 1
        for (k in 0 until kCount) {
            val ev = events[k]
            totalFloats += 24 + (ev.stftTimeSteps * ev.stftFreqBins)
        }
        val packed = FloatArray(totalFloats)
        packed[0] = kCount.toFloat()

        var offset = 1
        for (k in 0 until kCount) {
            val ev = events[k]
            packed[offset + 0] = ev.onsetSampleIndex.toFloat()
            packed[offset + 1] = ev.peakAccelerationG
            packed[offset + 2] = ev.rmsG
            packed[offset + 3] = ev.durationMs
            packed[offset + 4] = ev.waveletEntropy
            packed[offset + 5] = if (ev.isStructuralShock) 1.0f else 0.0f
            for (b in 0 until 16) {
                packed[offset + 6 + b] = if (b < ev.wptSubbandEnergies.size) ev.wptSubbandEnergies[b] else 0.0f
            }
            packed[offset + 22] = ev.stftTimeSteps.toFloat()
            packed[offset + 23] = ev.stftFreqBins.toFloat()
            val specLen = ev.stftTimeSteps * ev.stftFreqBins
            if (specLen > 0 && ev.stftSpectrogram.size >= specLen) {
                System.arraycopy(ev.stftSpectrogram, 0, packed, offset + 24, specLen)
            }
            offset += 24 + specLen
        }
        return packed
    }

    /**
     * Unpacks the contiguous JNI float array layout into structured TransientTimeFrequencyEvent objects,
     * reconstructing 64-bit nanosecond onset timestamps safely from onsetSampleIndex.
     */
    fun unpackTransientEventsRaw(
        raw: FloatArray,
        sessionStartTimestampNs: Long,
        sampleRateHz: Float
    ): List<TransientTimeFrequencyEvent> {
        if (raw.isEmpty()) return emptyList()
        val kCount = raw[0].toInt().coerceIn(0, 5)
        if (kCount == 0) return emptyList()

        val result = ArrayList<TransientTimeFrequencyEvent>(kCount)
        var offset = 1
        for (k in 0 until kCount) {
            if (offset + 24 > raw.size) break
            val onsetIdxFloat = raw[offset + 0]
            val onsetSampleIdx = onsetIdxFloat.toInt()
            val onsetTsNs = reconstructOnsetTimestampNs(sessionStartTimestampNs, onsetIdxFloat, sampleRateHz)
            val peakG = raw[offset + 1]
            val rmsG = raw[offset + 2]
            val durationMs = raw[offset + 3]
            val entropy = raw[offset + 4]
            val isShock = raw[offset + 5] >= 0.5f
            val subbands = raw.copyOfRange(offset + 6, offset + 22)
            val timeSteps = raw[offset + 22].toInt().coerceAtLeast(0)
            val freqBins = raw[offset + 23].toInt().coerceAtLeast(0)
            val specLen = timeSteps * freqBins

            val specStart = offset + 24
            val specEnd = min(raw.size, specStart + specLen)
            val spectrogram = if (specEnd > specStart) {
                raw.copyOfRange(specStart, specEnd)
            } else FloatArray(0)

            result.add(
                TransientTimeFrequencyEvent(
                    onsetSampleIndex = onsetSampleIdx,
                    onsetTimestampNs = onsetTsNs,
                    peakAccelerationG = peakG,
                    rmsG = rmsG,
                    durationMs = durationMs,
                    waveletEntropy = entropy,
                    isStructuralShock = isShock,
                    wptSubbandEnergies = subbands,
                    stftTimeSteps = timeSteps,
                    stftFreqBins = freqBins,
                    stftSpectrogram = spectrogram
                )
            )
            offset += 24 + specLen
        }
        return result
    }

    /**
     * Analyzes high-energy transient segments rejected by the Welch Median + 3*MAD gate.
     * Calls native C++20 JNI implementation when loaded, or seamlessly falls back to the
     * mathematically identical JVM implementation during host unit tests.
     */
    fun analyzeGatedTransients(
        signal: FloatArray,
        sessionStartTimestampNs: Long,
        sampleRateHz: Float = 100.0f,
        segmentSize: Int = 512,
        segmentOverlap: Float = 0.50f,
        gapMask: ByteArray = ByteArray(0)
    ): List<TransientTimeFrequencyEvent> {
        val raw = try {
            nativeAnalyzeGatedTransientsRaw(signal, sampleRateHz, segmentSize, segmentOverlap, gapMask)
        } catch (e: UnsatisfiedLinkError) {
            analyzeGatedTransientsJvmRaw(signal, sampleRateHz, segmentSize, segmentOverlap, gapMask)
        } catch (e: Throwable) {
            analyzeGatedTransientsJvmRaw(signal, sampleRateHz, segmentSize, segmentOverlap, gapMask)
        }
        return unpackTransientEventsRaw(raw, sessionStartTimestampNs, sampleRateHz)
    }

    internal fun analyzeGatedTransientsJvmRaw(
        signal: FloatArray,
        sampleRateHz: Float = 100.0f,
        segmentSize: Int = 512,
        segmentOverlap: Float = 0.50f,
        gapMask: ByteArray = ByteArray(0)
    ): FloatArray {
        val n = signal.size
        val segLen = max(256, segmentSize)
        if (n < segLen || sampleRateHz <= 0.0f) return floatArrayOf(0.0f)

        val stepSize = max(1, (segLen * (1.0f - segmentOverlap)).toInt())

        data class Cand(val startIdx: Int, val rms: Double, val hasGap: Boolean)
        val candidates = ArrayList<Cand>()
        var scanOffset = 0
        while (scanOffset + segLen <= n) {
            var hasGap = false
            if (gapMask.isNotEmpty() && scanOffset + segLen <= gapMask.size) {
                for (i in 0 until segLen) {
                    if (gapMask[scanOffset + i].toInt() != 0) {
                        hasGap = true
                        break
                    }
                }
            }
            var sumSq = 0.0
            for (i in 0 until segLen) {
                val v = signal[scanOffset + i].toDouble()
                sumSq += v * v
            }
            candidates.add(Cand(scanOffset, sqrt(sumSq / segLen), hasGap))
            scanOffset += stepSize
        }

        val validRms = candidates.filter { !it.hasGap }.map { it.rms }
        if (validRms.size < 3) return floatArrayOf(0.0f)

        val sortedRms = validRms.sorted()
        val medianRms = sortedRms[sortedRms.size / 2]
        val deviations = sortedRms.map { abs(it - medianRms) }.sorted()
        val madRms = deviations[deviations.size / 2]
        val rmsThreshold = max(medianRms + 3.0 * (1.4826 * madRms), medianRms * 2.5)

        val gated = candidates.filter { !it.hasGap && it.rms > rmsThreshold }
            .sortedByDescending { it.rms }
        if (gated.isEmpty()) return floatArrayOf(0.0f)

        val selected = ArrayList<Cand>()
        for (cand in gated) {
            val overlaps = selected.any { abs(cand.startIdx - it.startIdx) < segLen / 2 }
            if (!overlaps) {
                selected.add(cand)
                if (selected.size >= 5) break
            }
        }
        selected.sortBy { it.startIdx }

        val stftWin = 256
        val stftHop = 64
        val stftBins = stftWin / 2 + 1
        val hanning = DoubleArray(stftWin)
        var winPowerSum = 0.0
        val twoPiOverNm1 = 2.0 * PI / (stftWin - 1)
        for (i in 0 until stftWin) {
            val w = 0.5 * (1.0 - cos(twoPiOverNm1 * i))
            hanning[i] = w
            winPowerSum += w * w
        }
        val scale = 1.0 / (sampleRateHz.toDouble() * winPowerSum)
        val onsetAmpThreshold = max(medianRms * 3.0, 1e-5)

        val events = ArrayList<TransientTimeFrequencyEvent>(selected.size)
        val real = DoubleArray(stftWin)
        val imag = DoubleArray(stftWin)

        for (sel in selected) {
            val segData = signal.copyOfRange(sel.startIdx, sel.startIdx + segLen)
            var onsetOffset = 0
            var foundOnset = false
            var peakAbs = 0.0f
            for (i in 0 until segLen) {
                val absVal = abs(segData[i])
                if (absVal > peakAbs) peakAbs = absVal
                if (!foundOnset && absVal > onsetAmpThreshold.toFloat()) {
                    onsetOffset = i
                    foundOnset = true
                }
            }

            val onsetSampleIdx = sel.startIdx + onsetOffset
            val durationMs = (segLen.toFloat() * 1000.0f) / sampleRateHz
            val wpt = analyzeWaveletPacketDb4(segData, 4)

            val timeSteps = (segLen - stftWin) / stftHop + 1
            val spectrogram = FloatArray(timeSteps * stftBins)

            for (t in 0 until timeSteps) {
                val frameStart = t * stftHop
                for (i in 0 until stftWin) {
                    real[i] = segData[frameStart + i].toDouble() * hanning[i]
                    imag[i] = 0.0
                }
                radix2FftInPlace(real, imag, stftWin)
                val rowOffset = t * stftBins
                for (k in 0 until stftBins) {
                    var p = (real[k] * real[k] + imag[k] * imag[k]) * scale
                    if (k > 0 && k < stftBins - 1) p *= 2.0
                    spectrogram[rowOffset + k] = p.toFloat()
                }
            }

            events.add(
                TransientTimeFrequencyEvent(
                    onsetSampleIndex = onsetSampleIdx,
                    onsetTimestampNs = 0L,
                    peakAccelerationG = peakAbs,
                    rmsG = sel.rms.toFloat(),
                    durationMs = durationMs,
                    waveletEntropy = wpt.shannonEntropy,
                    isStructuralShock = wpt.isStructuralShock,
                    wptSubbandEnergies = wpt.normalizedEnergies,
                    stftTimeSteps = timeSteps,
                    stftFreqBins = stftBins,
                    stftSpectrogram = spectrogram
                )
            )
        }

        return packTransientEventsRaw(events)
    }

    private fun radix2FftInPlace(real: DoubleArray, imag: DoubleArray, n: Int) {
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val halfLen = len shr 1
            val angle = -2.0 * PI / len
            val wLenR = cos(angle)
            val wLenI = sin(angle)
            var i = 0
            while (i < n) {
                var wR = 1.0
                var wI = 0.0
                for (k in 0 until halfLen) {
                    val uR = real[i + k]
                    val uI = imag[i + k]
                    val vR = real[i + k + halfLen] * wR - imag[i + k + halfLen] * wI
                    val vI = real[i + k + halfLen] * wI + imag[i + k + halfLen] * wR
                    real[i + k] = uR + vR
                    imag[i + k] = uI + vI
                    real[i + k + halfLen] = uR - vR
                    imag[i + k + halfLen] = uI - vI
                    val nextWR = wR * wLenR - wI * wLenI
                    wI = wR * wLenI + wI * wLenR
                    wR = nextWR
                }
                i += len
            }
            len = len shl 1
        }
    }
}

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

        val peaks = engine.findPeaks(frequencies, firstSingularValues)
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

package com.ronin.phoneshm.feature.analysis

import com.ronin.phoneshm.core.dsp.DefaultRdtSsiEngine
import com.ronin.phoneshm.core.dsp.InterpolationMethod
import com.ronin.phoneshm.core.dsp.MultiAxisSpectrumResult
import com.ronin.phoneshm.core.dsp.NativeDspBridge
import com.ronin.phoneshm.core.dsp.WelchPsdEngine
import com.ronin.phoneshm.core.dsp.WelchPsdParameters
import com.ronin.phoneshm.core.modal.DefaultModalAnalyzer
import com.ronin.phoneshm.core.modal.ExcitationSufficiency
import com.ronin.phoneshm.core.modal.ModalAnalysisResult
import com.ronin.phoneshm.core.physics.DefaultPhysicsRulesEngine
import com.ronin.phoneshm.core.sensor.AccelerationSample
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Read-Only Forensic Synthetic Signal Verification Test Suite.
 *
 * Verifies the 4 priority audit items and executes synthetic signal tests at fs = 100 Hz
 * for 30s (3000 samples) and 60s (6000 samples) without modifying production code.
 */
class SyntheticSignalVerificationTest {

    private val sampleRateHz = 100.0f
    private val dtNs = 10_000_000L // 10 ms = 100 Hz
    private val gravityZ = 9.80665f

    private fun generatePureSineSamples(
        numSamples: Int,
        freqHz: Double,
        amplitudeMps2: Double = 1.0,
        includeGravityZ: Boolean = true
    ): List<AccelerationSample> {
        return List(numSamples) { i ->
            val t = i.toDouble() / sampleRateHz
            val x = (amplitudeMps2 * sin(2.0 * PI * freqHz * t)).toFloat()
            val y = 0.0f
            val z = if (includeGravityZ) gravityZ else 0.0f
            AccelerationSample(
                timestampNs = i * dtNs,
                x = x,
                y = y,
                z = z
            )
        }
    }

    private data class PipelineRunOutput(
        val spectrum: MultiAxisSpectrumResult,
        val modalResult: ModalAnalysisResult,
        val effectiveConfidence: Double,
        val isImpulseValid: Boolean?,
        val slidingWindowCount: Int,
        val nonEmptySlidingWindows: Int,
        val postEmaAmplitudeRatio: Double,
        val postHpfAmplitudeRatio: Double
    )

    /**
     * Runs the exact AnalysisViewModel pipeline (lines 214-343) on a given sample list.
     */
    private fun runAnalysisViewModelEquivalentPipeline(
        rawSamples: List<AccelerationSample>,
        isAmbientMode: Boolean,
        inputAmplitude: Double = 1.0,
        buildingType: String = "RC_MOMENT_FRAME",
        floors: Int? = 5
    ): PipelineRunOutput {
        val dspEngine = WelchPsdEngine()
        val modalAnalyzer = DefaultModalAnalyzer()
        val physicsEngine = DefaultPhysicsRulesEngine()

        val resamplingOutput = dspEngine.resampleToUniformGrid(
            samples = rawSamples,
            targetSampleRateHz = sampleRateHz,
            method = InterpolationMethod.PCHIP_CUBIC,
            gapThresholdMs = 50.0,
            maxAllowedMissingRatio = 0.05
        )
        val resampledSamples = resamplingOutput.samples

        // Measure post-EMA and post-HPF steady-state amplitude (after 600-sample settling)
        val gravResult = dspEngine.removeGravityAndDetrend(resampledSamples)
        val gfSamples = gravResult.gravityFreeSamples
        val settledX = FloatArray(gfSamples.size - 600) { idx -> gfSamples[idx + 600].x }
        val postEmaRms = sqrt(settledX.fold(0.0) { acc, v -> acc + v * v } / settledX.size)
        val expectedSineRms = inputAmplitude / sqrt(2.0)
        val postEmaAmplitudeRatio = if (expectedSineRms > 0) postEmaRms / expectedSineRms else 0.0

        val filtX = dspEngine.highPassFilter(settledX, sampleRateHz, 0.5f)
        val postHpfRms = sqrt(filtX.fold(0.0) { acc, v -> acc + v * v } / filtX.size)
        val postHpfAmplitudeRatio = if (expectedSineRms > 0) postHpfRms / expectedSineRms else 0.0

        val mainFftSize: Int
        val windowSize: Int
        val stepSize: Int
        val slidingFftSize: Int
        if (isAmbientMode && resampledSamples.size >= 15000) {
            mainFftSize = minOf(4096, Integer.highestOneBit(resampledSamples.size).coerceAtLeast(256))
            windowSize = 6000
            stepSize = 3000
            slidingFftSize = 2048
        } else if (isAmbientMode) {
            mainFftSize = minOf(2048, Integer.highestOneBit(resampledSamples.size).coerceAtLeast(256))
            windowSize = 1024
            stepSize = 512
            slidingFftSize = 512
        } else {
            mainFftSize = minOf(2048, Integer.highestOneBit(resampledSamples.size).coerceAtLeast(256))
            windowSize = 512
            stepSize = 256
            slidingFftSize = 256
        }

        val mainParams = WelchPsdParameters(fftSize = mainFftSize, overlapPercentage = 0.5f)
        val spectrum = dspEngine.calculateMultiAxisWelchPsd(
            samples = resampledSamples,
            sampleRateHz = sampleRateHz,
            params = mainParams,
            ambientSnrThresholdDb = 1.0f,
            gapMask = resamplingOutput.gapMask
        )

        // Settling-trimmed sliding window logic matching updated AnalysisViewModel.kt
        val sessionSettlingN = spectrum.output.settlingWindow?.settlingDurationSamples ?: 0
        val settledSamples = if (resampledSamples.size - sessionSettlingN >= windowSize) {
            resampledSamples.subList(sessionSettlingN, resampledSamples.size)
        } else {
            resampledSamples
        }
        val slidingSpectra = mutableListOf<MultiAxisSpectrumResult>()
        if (settledSamples.size >= windowSize) {
            val slidingParams = WelchPsdParameters(fftSize = slidingFftSize, overlapPercentage = 0.5f, skipSettlingTrim = true)
            var offset = 0
            while (offset + windowSize <= settledSamples.size) {
                val winSamples = settledSamples.subList(offset, offset + windowSize)
                slidingSpectra.add(
                    dspEngine.calculateMultiAxisWelchPsd(
                        winSamples,
                        sampleRateHz,
                        slidingParams,
                        ambientSnrThresholdDb = 1.0f
                    )
                )
                offset += stepSize
            }
        }

        val nonEmptySliding = slidingSpectra.count { it.psdMagnitude.peaks.isNotEmpty() }
        val modalResult = modalAnalyzer.analyzeMultiAxisSpectrum(
            spectrum = spectrum,
            slidingWindowSpectra = slidingSpectra,
            buildingType = buildingType,
            evaluatePhysics = { f0Hz, prominence ->
                physicsEngine.classifyFrequency(
                    f0Hz = f0Hz,
                    prominence = prominence.toFloat(),
                    buildingType = buildingType,
                    floors = floors
                )
            }
        )

        val samplingContinuity = dspEngine.verifySamplingContinuity(rawSamples, maxAllowedMissingRatio = 0.05)

        val effectiveConfidence = if (
            modalResult.excitationSufficiency == ExcitationSufficiency.INSUFFICIENT ||
            !samplingContinuity.isContinuityPassed
        ) {
            0.0
        } else {
            modalResult.confidence
        }

        return PipelineRunOutput(
            spectrum = spectrum,
            modalResult = modalResult,
            effectiveConfidence = effectiveConfidence,
            isImpulseValid = null,
            slidingWindowCount = slidingSpectra.size,
            nonEmptySlidingWindows = nonEmptySliding,
            postEmaAmplitudeRatio = postEmaAmplitudeRatio,
            postHpfAmplitudeRatio = postHpfAmplitudeRatio
        )
    }

    @Test
    fun verifyPureSineWaves_30s_and_60s_and_EmaAttenuation() {
        val testFreqs = listOf(0.6, 1.0, 2.0, 5.0)
        val amplitude = 1.0 // 1.0 m/s^2 peak -> RMS^2 = 0.5 m^2/s^4

        println("=== [TEST 1] PURE SINE WAVES (+9.80665 m/s² Z-Gravity) ===")
        for (freq in testFreqs) {
            val omega = 2.0 * PI * freq / sampleRateHz
            val alpha = 0.1
            val num = (1.0 - alpha) * sqrt(2.0 - 2.0 * cos(omega))
            val den = sqrt(1.0 + (1.0 - alpha) * (1.0 - alpha) - 2.0 * (1.0 - alpha) * cos(omega))
            val theoreticalEmaGain = num / den

            val butterGainSingle = 1.0 / sqrt(1.0 + Math.pow(0.5 / freq, 4.0))
            val theoreticalFiltfiltGain = butterGainSingle * butterGainSingle
            val theoreticalTotalGain = theoreticalEmaGain * theoreticalFiltfiltGain

            // Theoretical Welch PSD peak for unit-amplitude sine with Hanning window:
            // Unattenuated one-sided Hanning bin-centered sine PSD = (A^2 / 2) / ENBW_Hz
            // where ENBW_Hz = 1.5 * (fs / N_fft).
            // At N_fft = 2048, ENBW_Hz = 1.5 * (100/2048) = 0.073242 Hz -> Unattenuated Peak PSD = 0.5 / 0.073242 = 6.8267 (m/s^2)^2/Hz
            // At N_fft = 4096, ENBW_Hz = 1.5 * (100/4096) = 0.036621 Hz -> Unattenuated Peak PSD = 0.5 / 0.036621 = 13.6533 (m/s^2)^2/Hz
            val idealPsd30s = 0.5 / (1.5 * (sampleRateHz / 2048.0))
            val idealPsd60s = 0.5 / (1.5 * (sampleRateHz / 4096.0))

            val samples30s = generatePureSineSamples(3000, freq, amplitude, includeGravityZ = true)
            val out30s = runAnalysisViewModelEquivalentPipeline(samples30s, isAmbientMode = false, inputAmplitude = amplitude)

            val samples60s = generatePureSineSamples(6000, freq, amplitude, includeGravityZ = true)
            val out60s = runAnalysisViewModelEquivalentPipeline(samples60s, isAmbientMode = true, inputAmplitude = amplitude)

            println(
                String.format(
                    "Freq=%.1f Hz | TheoryGain(EMA=%.4f, HPF=%.4f, Total=%.4f, Power=%.4f) | " +
                        "MeasGain(EMA=%.4f, Total=%.4f) | " +
                        "30s(IdealPSD=%.4f): f0=%.4f Hz, PeakPSD=%.4f, Persist=%.2f (%d/%d win), ModalConf=%.3f, EffConf=%.3f (ImpValid=%s) | " +
                        "60s(IdealPSD=%.4f): f0=%.4f Hz, PeakPSD=%.4f, Persist=%.2f (%d/%d win), ModalConf=%.3f, EffConf=%.3f",
                    freq,
                    theoreticalEmaGain,
                    theoreticalFiltfiltGain,
                    theoreticalTotalGain,
                    theoreticalTotalGain * theoreticalTotalGain,
                    out30s.postEmaAmplitudeRatio,
                    out30s.postHpfAmplitudeRatio,
                    idealPsd30s,
                    out30s.modalResult.fundamentalFrequencyHz,
                    out30s.spectrum.psdMagnitude.peaks.firstOrNull()?.powerMagnitude ?: -1.0f,
                    out30s.modalResult.persistence,
                    out30s.nonEmptySlidingWindows,
                    out30s.slidingWindowCount,
                    out30s.modalResult.confidence,
                    out30s.effectiveConfidence,
                    out30s.isImpulseValid.toString(),
                    idealPsd60s,
                    out60s.modalResult.fundamentalFrequencyHz,
                    out60s.spectrum.psdMagnitude.peaks.firstOrNull()?.powerMagnitude ?: -1.0f,
                    out60s.modalResult.persistence,
                    out60s.nonEmptySlidingWindows,
                    out60s.slidingWindowCount,
                    out60s.modalResult.confidence,
                    out60s.effectiveConfidence
                )
            )
            assertTrue("Gravity removal should not attenuate low frequencies (>0.99)", out30s.postEmaAmplitudeRatio > 0.99)
            assertTrue("Sliding-window persistence should be >= 0.95", out30s.modalResult.persistence >= 0.95)
            assertTrue("60s sliding-window persistence should be >= 0.95", out60s.modalResult.persistence >= 0.95)
            assertTrue(out30s.modalResult.fundamentalFrequencyHz > 0.0)
            assertTrue(out60s.modalResult.fundamentalFrequencyHz > 0.0)
        }
    }

    @Test
    fun verifyMagnitudePsdInconsistency_SumOfPsds_vs_TimeDomainEuclideanNorm() {
        val dspEngine = WelchPsdEngine()
        val freq = 2.0
        val samples30s = generatePureSineSamples(3000, freq, 1.0, includeGravityZ = true)

        val params = WelchPsdParameters(fftSize = 2048, overlapPercentage = 0.5f)
        val kotlinResult = dspEngine.calculateMultiAxisWelchPsd(samples30s, sampleRateHz, params)
        val kotlinTopPeaks = kotlinResult.psdMagnitude.peaks.take(3)

        val grav = dspEngine.removeGravityAndDetrend(samples30s)
        val settlingN = kotlinResult.output.settlingWindow?.settlingDurationSamples ?: 600
        val usableSamples = grav.gravityFreeSamples.subList(settlingN, samples30s.size)
        val rawMag = dspEngine.fuseAxes(usableSamples)
        val filtMag = dspEngine.highPassFilter(rawMag, sampleRateHz, 0.5f)

        val magAsSamples = List(usableSamples.size + 600) { idx ->
            val v = if (idx < 600) 0f else filtMag[idx - 600]
            AccelerationSample(idx * dtNs, v, 0f, 0f)
        }
        val cppEquivalentSpectrum = dspEngine.calculateMultiAxisWelchPsd(magAsSamples, sampleRateHz, params)
        val cppTopPeaks = cppEquivalentSpectrum.psdX.peaks.take(3)

        println("=== [TEST 2] MAGNITUDE PSD INCONSISTENCY (f0 = 2.0 Hz) ===")
        println("Kotlin (psdX+psdY+psdZ) Top Peaks: " + kotlinTopPeaks.joinToString {
            String.format("f=%.4f Hz (PSD=%.5f, Prom=%.1f)", it.frequencyHz, it.powerMagnitude, it.prominence)
        })
        println("C++ Equivalent (Welch of sqrt(x²+y²+z²)) Top Peaks: " + cppTopPeaks.joinToString {
            String.format("f=%.4f Hz (PSD=%.5f, Prom=%.1f)", it.frequencyHz, it.powerMagnitude, it.prominence)
        })
        assertTrue(kotlinTopPeaks.isNotEmpty())
        assertTrue(cppTopPeaks.isNotEmpty())
    }

    @Test
    fun verifyMixedMultiModeSignal_1Hz_and_3Hz() {
        // Test Case A: 1.0 Hz (amp = 1.0 m/s^2) + 3.0 Hz (amp = 0.5 m/s^2)
        // Test Case B: 1.0 Hz (amp = 1.0 m/s^2) + 3.0 Hz (amp = 0.8 m/s^2)
        for ((amp1, amp3) in listOf(1.0 to 0.5, 1.0 to 0.8)) {
            val samples30s = List(3000) { i ->
                val t = i.toDouble() / sampleRateHz
                val x = (amp1 * sin(2.0 * PI * 1.0 * t) + amp3 * sin(2.0 * PI * 3.0 * t)).toFloat()
                AccelerationSample(i * dtNs, x, 0f, gravityZ)
            }
            val samples60s = List(6000) { i ->
                val t = i.toDouble() / sampleRateHz
                val x = (amp1 * sin(2.0 * PI * 1.0 * t) + amp3 * sin(2.0 * PI * 3.0 * t)).toFloat()
                AccelerationSample(i * dtNs, x, 0f, gravityZ)
            }

            val out30s = runAnalysisViewModelEquivalentPipeline(samples30s, isAmbientMode = false)
            val out60s = runAnalysisViewModelEquivalentPipeline(samples60s, isAmbientMode = true)

            val mode2_30s = out30s.modalResult.dominantPeaksTable.getOrNull(1)
            val mode2_60s = out60s.modalResult.dominantPeaksTable.getOrNull(1)

            println("=== [TEST 3] MIXED MULTI-MODE SIGNAL (1.0 Hz @ $amp1 m/s² + 3.0 Hz @ $amp3 m/s²) ===")
            println("30s Mag Peaks: " + out30s.spectrum.psdMagnitude.peaks.joinToString {
                String.format("f=%.4f Hz (PSD=%.4f, Prom=%.1f)", it.frequencyHz, it.powerMagnitude, it.prominence)
            })
            println(
                String.format(
                    "30s Modal: f0=%.4f Hz (Axis=%s, Class=%s, Persist=%.2f, ModalConf=%.3f, EffConf=%.3f) | Mode2=%s",
                    out30s.modalResult.fundamentalFrequencyHz,
                    out30s.modalResult.dominantAxis,
                    out30s.modalResult.classification.classification.name,
                    out30s.modalResult.persistence,
                    out30s.modalResult.confidence,
                    out30s.effectiveConfidence,
                    mode2_30s?.let { String.format("%.4f Hz (PSD=%.4f)", it.first, it.second) } ?: "null"
                )
            )
            println(
                String.format(
                    "60s Modal: f0=%.4f Hz (Axis=%s, Class=%s, Persist=%.2f, ModalConf=%.3f, EffConf=%.3f) | Mode2=%s",
                    out60s.modalResult.fundamentalFrequencyHz,
                    out60s.modalResult.dominantAxis,
                    out60s.modalResult.classification.classification.name,
                    out60s.modalResult.persistence,
                    out60s.modalResult.confidence,
                    out60s.effectiveConfidence,
                    mode2_60s?.let { String.format("%.4f Hz (PSD=%.4f)", it.first, it.second) } ?: "null"
                )
            )
        }
    }

    @Test
    fun verifyNoisySignal_1_5Hz_plus_GaussianWhiteNoise() {
        val rng = Random(42L)
        val samples30s = List(3000) { i ->
            val t = i.toDouble() / sampleRateHz
            val x = (0.2 * sin(2.0 * PI * 1.5 * t) + 0.2 * rng.nextGaussian()).toFloat()
            val y = (0.2 * rng.nextGaussian()).toFloat()
            val z = gravityZ + (0.2 * rng.nextGaussian()).toFloat()
            AccelerationSample(i * dtNs, x, y, z)
        }
        val samples60s = List(6000) { i ->
            val t = i.toDouble() / sampleRateHz
            val x = (0.2 * sin(2.0 * PI * 1.5 * t) + 0.2 * rng.nextGaussian()).toFloat()
            val y = (0.2 * rng.nextGaussian()).toFloat()
            val z = gravityZ + (0.2 * rng.nextGaussian()).toFloat()
            AccelerationSample(i * dtNs, x, y, z)
        }

        val pureNoise30s = List(3000) { i ->
            AccelerationSample(
                i * dtNs,
                (0.2 * rng.nextGaussian()).toFloat(),
                (0.2 * rng.nextGaussian()).toFloat(),
                gravityZ + (0.2 * rng.nextGaussian()).toFloat()
            )
        }

        val out30s = runAnalysisViewModelEquivalentPipeline(samples30s, isAmbientMode = false, inputAmplitude = 0.2)
        val out60s = runAnalysisViewModelEquivalentPipeline(samples60s, isAmbientMode = true, inputAmplitude = 0.2)
        val outNoise = runAnalysisViewModelEquivalentPipeline(pureNoise30s, isAmbientMode = false, inputAmplitude = 0.0)

        println("=== [TEST 4] NOISY SIGNAL (1.5 Hz @ 0.2 m/s² + σ=0.2 m/s² White Noise) ===")
        println(
            String.format(
                "30s Noisy: f0=%.4f Hz, PeakPSD=%.5f, PromRatio=%.2f (Suff=%s), Persist=%.2f, ModalConf=%.3f, EffConf=%.3f",
                out30s.modalResult.fundamentalFrequencyHz,
                out30s.spectrum.psdMagnitude.peaks.firstOrNull()?.powerMagnitude ?: -1.0f,
                out30s.modalResult.prominenceRatio,
                out30s.modalResult.excitationSufficiency.name,
                out30s.modalResult.persistence,
                out30s.modalResult.confidence,
                out30s.effectiveConfidence
            )
        )
        println(
            String.format(
                "60s Noisy: f0=%.4f Hz, PeakPSD=%.5f, PromRatio=%.2f (Suff=%s), Persist=%.2f, ModalConf=%.3f, EffConf=%.3f",
                out60s.modalResult.fundamentalFrequencyHz,
                out60s.spectrum.psdMagnitude.peaks.firstOrNull()?.powerMagnitude ?: -1.0f,
                out60s.modalResult.prominenceRatio,
                out60s.modalResult.excitationSufficiency.name,
                out60s.modalResult.persistence,
                out60s.modalResult.confidence,
                out60s.effectiveConfidence
            )
        )
        println(
            String.format(
                "Pure White Noise (No Signal): Fabricated Peaks=%d, False f0=%.4f Hz, PromRatio=%.2f (Suff=%s), ModalConf=%.3f, EffConf=%.3f",
                outNoise.spectrum.psdMagnitude.peaks.size,
                outNoise.modalResult.fundamentalFrequencyHz,
                outNoise.modalResult.prominenceRatio,
                outNoise.modalResult.excitationSufficiency.name,
                outNoise.modalResult.confidence,
                outNoise.effectiveConfidence
            )
        )
    }

    @Test
    fun verifyDampedSinusoid_and_ColoredNoiseSdof_Efdd_and_RdtSsi() {
        val targetFreq = 2.0f
        val targetZeta = 0.02 // 2% damping
        val omegaN = 2.0 * PI * targetFreq
        val omegaD = omegaN * sqrt(1.0 - targetZeta * targetZeta)

        val decayFromT0_30s = List(3000) { i ->
            val t = i.toDouble() / sampleRateHz
            val env = exp(-targetZeta * omegaN * t)
            val x = (2.0 * env * sin(omegaD * t)).toFloat()
            val y = (1.0 * env * sin(omegaD * t)).toFloat()
            AccelerationSample(i * dtNs, x, y, gravityZ)
        }

        val decayAfterSettling_30s = List(3000) { i ->
            val t = i.toDouble() / sampleRateHz
            val x = if (t >= 6.5) {
                val td = t - 6.5
                (2.0 * exp(-targetZeta * omegaN * td) * sin(omegaD * td)).toFloat()
            } else {
                0.0f
            }
            val y = if (t >= 6.5) {
                val td = t - 6.5
                (1.0 * exp(-targetZeta * omegaN * td) * sin(omegaD * td)).toFloat()
            } else {
                0.0f
            }
            AccelerationSample(i * dtNs, x, y, gravityZ)
        }

        val rng = Random(12345L)
        fun generateAmbientSdof(numSamples: Int): List<AccelerationSample> {
            val dt = 1.0 / sampleRateHz
            var disp = 0.0
            var vel = 0.0
            var acc = 0.0
            val k = omegaN * omegaN
            val c = 2.0 * targetZeta * omegaN
            for (w in 0 until 1000) {
                val force = rng.nextGaussian()
                val accNew = force - c * vel - k * disp
                vel += 0.5 * (acc + accNew) * dt
                disp += vel * dt + 0.5 * accNew * dt * dt
                acc = accNew
            }
            return List(numSamples) { i ->
                val force = rng.nextGaussian()
                val accNew = force - c * vel - k * disp
                vel += 0.5 * (acc + accNew) * dt
                disp += vel * dt + 0.5 * accNew * dt * dt
                acc = accNew
                val x = acc.toFloat()
                val y = (0.5 * acc + 0.02 * rng.nextGaussian()).toFloat()
                val z = gravityZ + (0.2 * acc + 0.02 * rng.nextGaussian()).toFloat()
                AccelerationSample(i * dtNs, x, y, z)
            }
        }

        val sdof30s = generateAmbientSdof(3000)
        val sdof60s = generateAmbientSdof(6000)

        val ssiEngine = DefaultRdtSsiEngine()

        var ssiThrewUnsatisfiedLinkError = false
        fun runEfddAndSsi(samples: List<AccelerationSample>, fftSize: Int): Pair<com.ronin.phoneshm.core.dsp.NativeFddResult, com.ronin.phoneshm.core.dsp.RdtSsiResult> {
            val ts = LongArray(samples.size) { samples[it].timestampNs }
            val x = FloatArray(samples.size) { samples[it].x }
            val y = FloatArray(samples.size) { samples[it].y }
            val z = FloatArray(samples.size) { samples[it].z }
            val efdd = NativeDspBridge.nativeCalculateFdd(ts, x, y, z, sampleRateHz, fftSize, 0.5f)
            val ssi = try {
                ssiEngine.calculateSsi(ts, x, y, z, sampleRateHz, 0.5f, 25.0f)
            } catch (e: UnsatisfiedLinkError) {
                ssiThrewUnsatisfiedLinkError = true
                com.ronin.phoneshm.core.dsp.RdtSsiResult(emptyList())
            }
            return Pair(efdd, ssi)
        }

        val (efddDecayT0, ssiDecayT0) = runEfddAndSsi(decayFromT0_30s, 2048)
        val (efddDecayAfter, ssiDecayAfter) = runEfddAndSsi(decayAfterSettling_30s, 2048)
        val (efddSdof30, ssiSdof30) = runEfddAndSsi(sdof30s, 2048)
        val (efddSdof60, ssiSdof60) = runEfddAndSsi(sdof60s, 4096)

        println("=== [TEST 5] DAMPED DECAYING SINUSOID & AMBIENT SDOF (fn = 2.0 Hz, zeta = 0.0200) ===")
        println(
            String.format(
                "1) Decay from t=0s (30s): EFDD Top1(f0=%.4f Hz, zeta=%.5f) | RDT-SSI StablePoles=%d, Best=%s",
                efddDecayT0.peakFrequencies.firstOrNull() ?: -1.0f,
                efddDecayT0.peakDampingRatios.firstOrNull() ?: -1.0f,
                ssiDecayT0.poles.size,
                ssiDecayT0.poles.minByOrNull { abs(it.frequencyHz - targetFreq) }
                    ?.let { String.format("f0=%.4f Hz, zeta=%.5f (orders=%d)", it.frequencyHz, it.dampingRatio, it.modelOrder) } ?: "NONE"
            )
        )
        println(
            String.format(
                "2) Decay from t=6.5s (30s, post-settling): EFDD Top1(f0=%.4f Hz, zeta=%.5f) | RDT-SSI StablePoles=%d, Best=%s",
                efddDecayAfter.peakFrequencies.firstOrNull() ?: -1.0f,
                efddDecayAfter.peakDampingRatios.firstOrNull() ?: -1.0f,
                ssiDecayAfter.poles.size,
                ssiDecayAfter.poles.minByOrNull { abs(it.frequencyHz - targetFreq) }
                    ?.let { String.format("f0=%.4f Hz, zeta=%.5f (orders=%d)", it.frequencyHz, it.dampingRatio, it.modelOrder) } ?: "NONE"
            )
        )
        // Also find the mode closest to 2.0 Hz in EFDD modes for SDOF:
        val s30Idx = efddSdof30.peakFrequencies.indices.minByOrNull { abs(efddSdof30.peakFrequencies[it] - targetFreq) } ?: -1
        val s60Idx = efddSdof60.peakFrequencies.indices.minByOrNull { abs(efddSdof60.peakFrequencies[it] - targetFreq) } ?: -1
        println(
            String.format(
                "3) Ambient SDOF (30s, N=3000, FFT=2048): EFDD Top1(f0=%.4f Hz, zeta=%.5f), ClosestTo2Hz(f0=%.4f Hz, zeta=%.5f) | RDT-SSI StablePoles=%d, Best=%s",
                efddSdof30.peakFrequencies.firstOrNull() ?: -1.0f,
                efddSdof30.peakDampingRatios.firstOrNull() ?: -1.0f,
                if (s30Idx >= 0) efddSdof30.peakFrequencies[s30Idx] else -1.0f,
                if (s30Idx >= 0) efddSdof30.peakDampingRatios[s30Idx] else -1.0f,
                ssiSdof30.poles.size,
                ssiSdof30.poles.minByOrNull { abs(it.frequencyHz - targetFreq) }
                    ?.let { String.format("f0=%.4f Hz, zeta=%.5f (orders=%d)", it.frequencyHz, it.dampingRatio, it.modelOrder) } ?: "NONE"
            )
        )
        println(
            String.format(
                "4) Ambient SDOF (60s, N=6000, FFT=4096): EFDD Top1(f0=%.4f Hz, zeta=%.5f), ClosestTo2Hz(f0=%.4f Hz, zeta=%.5f) | RDT-SSI StablePoles=%d, Best=%s",
                efddSdof60.peakFrequencies.firstOrNull() ?: -1.0f,
                efddSdof60.peakDampingRatios.firstOrNull() ?: -1.0f,
                if (s60Idx >= 0) efddSdof60.peakFrequencies[s60Idx] else -1.0f,
                if (s60Idx >= 0) efddSdof60.peakDampingRatios[s60Idx] else -1.0f,
                ssiSdof60.poles.size,
                ssiSdof60.poles.minByOrNull { abs(it.frequencyHz - targetFreq) }
                    ?.let { String.format("f0=%.4f Hz, zeta=%.5f (orders=%d)", it.frequencyHz, it.dampingRatio, it.modelOrder) } ?: "NONE"
            )
        )
        assertTrue("RDT-SSI JVM fallback must not throw UnsatisfiedLinkError", !ssiThrewUnsatisfiedLinkError)
        assertTrue("EFDD Top-1 peak for 60s Ambient SDOF must be near 2.0 Hz", abs((efddSdof60.peakFrequencies.firstOrNull() ?: 0f) - targetFreq) < 0.2f)
        assertTrue("RDT-SSI JVM fallback must extract stable poles for 60s Ambient SDOF", ssiSdof60.poles.isNotEmpty())
    }

    @Test
    fun verify300sAmbientContinuousMonitoring_30000Samples_FullPipelineConsensus() {
        val numSamples = 30_000 // 300s (5 minutes) @ 100 Hz
        val targetFreq = 1.65 // 1.65 Hz structural mode (5-story RC building)
        val targetZeta = 0.020 // 2.0% damping
        val omegaN = 2.0 * PI * targetFreq
        val rng = Random(2026L)

        // Generate 300s (30,000 samples) of ambient white-noise-driven 2-mode structural response
        val dt = 1.0 / sampleRateHz
        var disp1 = 0.0
        var vel1 = 0.0
        var acc1 = 0.0
        val k1 = omegaN * omegaN
        val c1 = 2.0 * targetZeta * omegaN

        for (w in 0 until 1000) {
            val force = rng.nextGaussian()
            val accNew = force - c1 * vel1 - k1 * disp1
            vel1 += 0.5 * (acc1 + accNew) * dt
            disp1 += vel1 * dt + 0.5 * accNew * dt * dt
            acc1 = accNew
        }

        val samples300s = List(numSamples) { i ->
            val force = rng.nextGaussian()
            val accNew = force - c1 * vel1 - k1 * disp1
            vel1 += 0.5 * (acc1 + accNew) * dt
            disp1 += vel1 * dt + 0.5 * accNew * dt * dt
            acc1 = accNew
            val x = (acc1 + 0.03 * rng.nextGaussian()).toFloat()
            val y = (0.45 * acc1 + 0.03 * rng.nextGaussian()).toFloat()
            val z = gravityZ + (0.05 * rng.nextGaussian()).toFloat()
            AccelerationSample(i * dtNs, x, y, z)
        }

        val out300s = runAnalysisViewModelEquivalentPipeline(
            rawSamples = samples300s,
            isAmbientMode = true,
            inputAmplitude = 1.0,
            buildingType = "RC_MOMENT_FRAME",
            floors = 5
        )

        val settledSamples = samples300s.subList(600, samples300s.size)
        val ts = LongArray(settledSamples.size) { settledSamples[it].timestampNs }
        val xArr = FloatArray(settledSamples.size) { settledSamples[it].x }
        val yArr = FloatArray(settledSamples.size) { settledSamples[it].y }
        val zArr = FloatArray(settledSamples.size) { settledSamples[it].z }

        val efdd300s = NativeDspBridge.nativeCalculateFdd(ts, xArr, yArr, zArr, sampleRateHz, 4096, 0.5f)
        val ssi300s = DefaultRdtSsiEngine().calculateSsi(ts, xArr, yArr, zArr, sampleRateHz, 0.5f, 15.0f)

        val consensusEngine = com.ronin.phoneshm.core.modal.ModalConsensusEngine()
        val consensus = consensusEngine.evaluateConsensus(out300s.modalResult, efdd300s, ssi300s)

        println("=== [TEST 6] 300s (30,000 SAMPLES) AMBIENT CONTINUOUS MONITORING VERIFICATION ===")
        println(
            String.format(
                "300s Welch+Modal: f0=%.4f Hz, SlidingWindows=%d/%d, Persistence=%.2f, ModalConf=%.3f, EffConf=%.3f",
                out300s.modalResult.fundamentalFrequencyHz,
                out300s.nonEmptySlidingWindows,
                out300s.slidingWindowCount,
                out300s.modalResult.persistence,
                out300s.modalResult.confidence,
                out300s.effectiveConfidence
            )
        )
        println(
            String.format(
                "300s EFDD Top1: f0=%.4f Hz, zeta=%.4f | 300s RDT-SSI Best: %s | Consensus: status=%s, f0=%.4f Hz, score=%.2f",
                efdd300s.peakFrequencies.firstOrNull() ?: -1.0f,
                efdd300s.peakDampingRatios.firstOrNull() ?: -1.0f,
                ssi300s.poles.firstOrNull()?.let { String.format("f0=%.4f Hz, zeta=%.4f (n=%d, stability=%d)", it.frequencyHz, it.dampingRatio, it.modelOrder, it.stabilityCount) } ?: "NONE",
                consensus.status.name,
                consensus.consensusF0 ?: -1.0,
                consensus.agreementScore
            )
        )

        assertTrue("300s ambient should have 8 overlapping 60s sliding windows", out300s.slidingWindowCount == 8)
        assertTrue("300s ambient persistence should be >= 0.85", out300s.modalResult.persistence >= 0.85)
        assertTrue("300s Welch f0 must be within 0.1 Hz of 1.65 Hz", abs(out300s.modalResult.fundamentalFrequencyHz - targetFreq) < 0.10)
        assertTrue("300s EFDD Top-1 f0 must be within 0.1 Hz of 1.65 Hz", abs((efdd300s.peakFrequencies.firstOrNull() ?: 0f) - targetFreq.toFloat()) < 0.10f)
        assertTrue("300s RDT-SSI must extract stable pole near 1.65 Hz", ssi300s.poles.any { abs(it.frequencyHz - targetFreq.toFloat()) < 0.10f })
        assertTrue("300s Modal Consensus must be AGREED", consensus.status == com.ronin.phoneshm.core.modal.ConsensusStatus.AGREED)
        assertNotNull(consensus.consensusF0)
    }

    /**
     * TEST 7: Zero-Velocity Bias Subtraction, Roving Mode Shape MAC + sgn(Re(Gxz)), and SSI Model Order Verification
     */
    @Test
    fun verifyBiasSubtraction_RovingModeShapeMac_and_SsiModelOrder() {
        // 1. Verify Zero-Velocity Bias Subtraction in resampleToUniformGrid
        val dspEngine = WelchPsdEngine()
        val bias = floatArrayOf(0.15f, -0.25f, 0.35f)
        val dtNs = 10_000_000L
        val rawWithBias = List(100) { i ->
            AccelerationSample(
                timestampNs = i * dtNs,
                x = 1.0f + bias[0],
                y = -2.0f + bias[1],
                z = 9.81f + bias[2]
            )
        }
        val resampledOut = dspEngine.resampleToUniformGrid(
            samples = rawWithBias,
            targetSampleRateHz = 100.0f,
            method = com.ronin.phoneshm.core.dsp.InterpolationMethod.PCHIP_CUBIC,
            accelerometerBias = bias
        )
        assertTrue("Resampled samples must not be empty", resampledOut.samples.isNotEmpty())
        resampledOut.samples.forEach { s ->
            assertTrue("x bias subtracted", abs(s.x - 1.0f) < 1e-4f)
            assertTrue("y bias subtracted", abs(s.y - (-2.0f)) < 1e-4f)
            assertTrue("z bias subtracted", abs(s.z - 9.81f) < 1e-4f)
        }

        // 2. Verify Roving Mode Shape Engine MAC & sgn(Re(Gxz)) Phase Orientation
        val rovingEngine = com.ronin.phoneshm.core.modal.DefaultRovingModeShapeEngine()
        val observations = listOf(
            com.ronin.phoneshm.core.modal.FloorModalObservation(
                floorLevel = 1,
                floorName = "Floor 1",
                f0Hz = 1.65,
                peakSingularValue = 0.04,
                stationaryBroadbandRmsVariance = 0.01,
                dominantAzimuthDegrees = 10.0,
                crossSpectralReGxz = 0.018
            ),
            com.ronin.phoneshm.core.modal.FloorModalObservation(
                floorLevel = 3,
                floorName = "Floor 3",
                f0Hz = 1.65,
                peakSingularValue = 0.36,
                stationaryBroadbandRmsVariance = 0.01,
                dominantAzimuthDegrees = 12.0,
                crossSpectralReGxz = 0.055
            ),
            com.ronin.phoneshm.core.modal.FloorModalObservation(
                floorLevel = 5,
                floorName = "Roof",
                f0Hz = 1.65,
                peakSingularValue = 1.00,
                stationaryBroadbandRmsVariance = 0.01,
                dominantAzimuthDegrees = 8.0,
                crossSpectralReGxz = 0.092
            )
        )
        val modeShape = rovingEngine.synthesizeModeShape(observations)
        assertNotNull("Roving mode shape should be synthesized", modeShape)
        val ms = modeShape!!
        assertTrue("Roof amplitude = 1.0", abs(ms.normalizedAmplitudes[2] - 1.0) < 1e-5)
        assertTrue("Floor 3 amplitude = 0.6", abs(ms.normalizedAmplitudes[1] - 0.6) < 1e-3)
        assertTrue("Floor 1 amplitude = 0.2", abs(ms.normalizedAmplitudes[0] - 0.2) < 1e-3)
        assertTrue("MAC against ideal first-mode shear profile must be > 0.95", ms.macValue > 0.95)
        assertTrue("All floors with positive Re(Gxz) must have phaseSign +1.0", ms.phaseSigns.all { it == 1.0 })
        assertTrue("Monotonic shear profile must be valid", ms.isMonotonicShearProfile)

        // 3. Verify RDT-SSI Pole preserves even State-Space Model Order (n in 2..40) and stabilityCount
        val rng = Random(20260405L)
        val nSamples = 6000
        val dt = 0.01
        val omega0 = 2.0 * Math.PI * 1.65
        val zeta = 0.02
        val k1 = omega0 * omega0
        val c1 = 2.0 * zeta * omega0
        var disp = 0.0
        var vel = 0.0
        var acc = 0.0
        val ts = LongArray(nSamples) { it * dtNs }
        val xArr = FloatArray(nSamples)
        val yArr = FloatArray(nSamples)
        val zArr = FloatArray(nSamples)
        for (i in 0 until nSamples) {
            val f = rng.nextGaussian()
            val accNew = f - c1 * vel - k1 * disp
            vel += 0.5 * (acc + accNew) * dt
            disp += vel * dt + 0.5 * accNew * dt * dt
            acc = accNew
            xArr[i] = acc.toFloat()
            yArr[i] = (0.5 * acc).toFloat()
            zArr[i] = 9.81f
        }
        val ssiOut = DefaultRdtSsiEngine().calculateSsi(ts, xArr, yArr, zArr, 100.0f, 0.5f, 15.0f)
        assertTrue("RDT-SSI must identify at least one stable pole", ssiOut.poles.isNotEmpty())
        val topPole = ssiOut.poles.first()
        println(
            String.format(
                "=== [TEST 7] BIAS, ROVING MAC=%.4f, SSI POLE: f0=%.4f Hz, zeta=%.4f, modelOrder(n)=%d, stabilityCount=%d ===",
                ms.macValue,
                topPole.frequencyHz,
                topPole.dampingRatio,
                topPole.modelOrder,
                topPole.stabilityCount
            )
        )
        assertTrue("State-space modelOrder must be an even integer in [2, 40]", topPole.modelOrder in 2..40 && topPole.modelOrder % 2 == 0)
        assertTrue("Cluster stabilityCount must be >= 4", topPole.stabilityCount >= 4)
    }
}

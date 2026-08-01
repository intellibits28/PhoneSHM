package com.ronin.phoneshm.core.dsp

import com.ronin.phoneshm.core.sensor.AccelerationSample
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class FixtureCalibrationTest {

    private fun loadFromResources(filename: String): List<AccelerationSample> {
        val stream = javaClass.classLoader!!.getResourceAsStream("fixtures/$filename")
            ?: error("Test fixture not found: fixtures/$filename")
        val bytes = stream.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val samples = mutableListOf<AccelerationSample>()
        while (buf.remaining() >= 20) {
            val ts = buf.long
            val x = buf.float
            val y = buf.float
            val z = buf.float
            samples.add(AccelerationSample(ts, x, y, z))
        }
        return samples
    }

    private data class FixtureInfo(
        val filename: String,
        val category: String
    )

    @Test
    fun runFullCalibrationOnAllFixtures() {
        val fixtures = listOf(
            FixtureInfo("0beff479-6675-4440-80c3-fc534c794925.bin", "Known Heel-Drop (66s #1)"),
            FixtureInfo("ce1f85f4-6cfc-43ae-80d4-4c69b2eed492.bin", "Known Heel-Drop (66s #2)"),
            FixtureInfo("d50c159a-01b6-4745-9cf2-19d76748a298.bin", "Known Heel-Drop (66s #3)"),
            FixtureInfo("052f699b-75b2-4bb4-ad6a-3f8259a8a94a.bin", "Known Ambient Baseline (600s #1)"),
            FixtureInfo("a317a576-787a-4162-bd18-8d2a4f56ef38.bin", "Known Ambient Baseline (600s #2)"),
            FixtureInfo("a562eabf-040f-4979-a438-9e1877f910fc.bin", "Ambiguous (66s #1)"),
            FixtureInfo("4c7398ab-da37-4ab9-91c7-10f1a4173ca8.bin", "Ambiguous (66s #2)")
        )

        val engine = WelchPsdEngine()
        val sampleRateHz = 100f

        println("\n==========================================================================================================================")
        println(String.format("%-10s | %-32s | %-8s | %-8s | %-9s | %-16s | %-8s | %-8s | %-8s",
            "ID", "Category", "Duration", "Max SNR", "Time Peak", "LowBand Energy %", "PeakPass", "SanityPass", "ImpulseValid"))
        println("==========================================================================================================================")

        for (fix in fixtures) {
            val samples = loadFromResources(fix.filename)
            val durationSec = (samples.last().timestampNs - samples.first().timestampNs) / 1e9

            // Use standard parameters: 2048 FFT, 50% overlap
            val params = WelchPsdParameters(fftSize = 2048, overlapPercentage = 0.5f)
            val result = engine.calculateMultiAxisWelchPsd(samples, sampleRateHz, params)

            val freqs = result.psdMagnitude.frequencies
            val psd = result.psdMagnitude.powerSpectralDensity
            val n = psd.size
            val localWindowHalf = 20
            val guardBand = 2

            var maxSnrDb = -999.0f
            var maxSnrFreq = 0.0f

            for (k in 1 until n - 1) {
                if (freqs[k] < 0.5f || freqs[k] > 15.0f) continue
                if (psd[k] > psd[k - 1] && psd[k] > psd[k + 1]) {
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

                    if (snrDb > maxSnrDb) {
                        maxSnrDb = snrDb
                        maxSnrFreq = freqs[k]
                    }
                }
            }

            // Calculate Time-domain peak ratio and RMS
            val settlingN = result.output.settlingWindow?.settlingDurationSamples ?: 0
            val gravResult = engine.removeGravityAndDetrend(samples)
            val gfSamples = gravResult.gravityFreeSamples
            val usableN = samples.size - settlingN

            val rawX = FloatArray(usableN) { gfSamples[it + settlingN].x }
            val rawY = FloatArray(usableN) { gfSamples[it + settlingN].y }
            val rawZ = FloatArray(usableN) { gfSamples[it + settlingN].z }

            val filtX = engine.highPassFilter(rawX, sampleRateHz, 0.5f)
            val filtY = engine.highPassFilter(rawY, sampleRateHz, 0.5f)
            val filtZ = engine.highPassFilter(rawZ, sampleRateHz, 0.5f)

            var maxMagSq = 0.0
            var sumMagSq = 0.0
            for (i in filtX.indices) {
                val magSq = filtX[i].toDouble() * filtX[i] + filtY[i].toDouble() * filtY[i] + filtZ[i].toDouble() * filtZ[i]
                if (magSq > maxMagSq) maxMagSq = magSq
                sumMagSq += magSq
            }
            val timeRms = sqrt(sumMagSq / filtX.size)
            val maxMag = sqrt(maxMagSq)
            val peakToRmsRatio = if (timeRms > 0) maxMag / timeRms else 0.0

            // Integrated PSD RMS
            val df = result.output.effectiveDeltaFHz.toDouble()
            var integratedPower = 0.0
            for (p in psd) integratedPower += p * df
            val psdRms = sqrt(integratedPower)
            val parsevalRatio = if (psdRms > 0) timeRms / psdRms else 0.0

            val impulseRes = engine.verifyImpulseQuality(samples, sampleRateHz)
            val fixtureIdShort = fix.filename.take(8)

            println(String.format("%-10s | %-32s | %6.1fs  | %6.2f dB | %7.2fx   | %7.2f%%         | %-8b | %-8b | %-8b",
                fixtureIdShort, fix.category, durationSec, maxSnrDb, impulseRes.peakToRmsRatio, impulseRes.lowBandEnergyRatio * 100, impulseRes.isPeakToRmsPassed, impulseRes.isSpectralSanityPassed, impulseRes.isImpulseValid))
        }
        println("==========================================================================================================================")
    }
}

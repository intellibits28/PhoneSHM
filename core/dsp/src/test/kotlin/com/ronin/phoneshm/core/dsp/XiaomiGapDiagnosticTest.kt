package com.ronin.phoneshm.core.dsp

import com.ronin.phoneshm.core.sensor.AccelerationSample
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

class XiaomiGapDiagnosticTest {

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

    data class FixtureTarget(val filename: String, val label: String)

    @Test
    fun analyzeSensorGapsOnFixtures() {
        val targets = listOf(
            FixtureTarget("53672631-acf0-49c9-8eb2-1f0940cbc957.bin", "Xiaomi Ambient #1"),
            FixtureTarget("549d1e10-afea-4ebe-8a4d-e6cb197b0c0e.bin", "Xiaomi Ambient #2"),
            FixtureTarget("25eb8686-90ef-4f8c-b519-fd4a932b3110.bin", "Note8 Ambient #1 (Control)"),
            FixtureTarget("46d2054f-2cc7-452c-8bb2-52added20478.bin", "Note8 Ambient #2 (Control)")
        )

        println("\n==========================================================================================================================")
        println("SENSOR SAMPLING GAP DIAGNOSTIC REPORT")
        println("==========================================================================================================================")

        for (target in targets) {
            val samples = loadFromResources(target.filename)
            if (samples.isEmpty()) {
                println("${target.label}: NO SAMPLES FOUND")
                continue
            }

            val totalDurationSec = (samples.last().timestampNs - samples.first().timestampNs) / 1e9
            val sampleCount = samples.size
            val expectedSamplesAt100Hz = (totalDurationSec * 100).toInt()

            var gap50Count = 0
            var gap50TotalMs = 0.0
            var maxGapMs = 0.0
            var minGapMs = Double.MAX_VALUE
            var isMonotonic = true

            val gapBins = mutableMapOf<String, Int>(
                "0-20ms" to 0,
                "20-50ms" to 0,
                "50-100ms" to 0,
                "100-180ms" to 0,
                "180-220ms (~199ms)" to 0,
                "220-500ms" to 0,
                ">500ms" to 0
            )

            val gapDurationsMs = mutableListOf<Double>()

            var firstGapTimeSec = -1.0
            val firstSampleTs = samples.first().timestampNs

            for (i in 1 until samples.size) {
                val dtNs = samples[i].timestampNs - samples[i - 1].timestampNs
                if (dtNs <= 0) {
                    isMonotonic = false
                }
                val dtMs = dtNs / 1e6
                if (dtMs > maxGapMs) maxGapMs = dtMs
                if (dtMs < minGapMs) minGapMs = dtMs

                when {
                    dtMs < 20 -> gapBins["0-20ms"] = gapBins["0-20ms"]!! + 1
                    dtMs in 20.0..50.0 -> gapBins["20-50ms"] = gapBins["20-50ms"]!! + 1
                    dtMs in 50.0..100.0 -> gapBins["50-100ms"] = gapBins["50-100ms"]!! + 1
                    dtMs in 100.0..180.0 -> gapBins["100-180ms"] = gapBins["100-180ms"]!! + 1
                    dtMs in 180.0..220.0 -> gapBins["180-220ms (~199ms)"] = gapBins["180-220ms (~199ms)"]!! + 1
                    dtMs in 220.0..500.0 -> gapBins["220-500ms"] = gapBins["220-500ms"]!! + 1
                    else -> gapBins[">500ms"] = gapBins[">500ms"]!! + 1
                }

                if (dtMs > 50.0) {
                    if (firstGapTimeSec < 0) {
                        firstGapTimeSec = (samples[i - 1].timestampNs - firstSampleTs) / 1e9
                    }
                    gap50Count++
                    gap50TotalMs += dtMs
                    gapDurationsMs.add(dtMs)
                }
            }

            val gapTimeSec = gap50TotalMs / 1000.0
            val missingTimePct = (gapTimeSec / totalDurationSec) * 100.0
            val avgGapMs = if (gapDurationsMs.isNotEmpty()) gapDurationsMs.average() else 0.0

            val dspEngine = WelchPsdEngine()
            val continuityResult = dspEngine.verifySamplingContinuity(samples)

            println("FIXTURE: ${target.filename} (${target.label})")
            println("  Total Duration    : ${String.format("%.2f", totalDurationSec)} s")
            println("  Sample Count      : $sampleCount (Expected @ 100Hz: ~$expectedSamplesAt100Hz)")
            println("  Monotonicity      : ${if (isMonotonic) "STRICTLY MONOTONIC (PASSED)" else "CORRUPTED (FAILED)"}")
            println("  First Gap Onset   : ${if (firstGapTimeSec >= 0) String.format("%.3f s (t ≈ %.2f s)", firstGapTimeSec, firstGapTimeSec) else "N/A (No gaps > 50ms)"}")
            println("  Gaps > 50ms Count : $gap50Count")
            println("  Total Missing Time: ${String.format("%.2f", gapTimeSec)} s (${String.format("%.2f", missingTimePct)}% of total recording)")
            println("  Average Gap (>50ms): ${String.format("%.2f", avgGapMs)} ms")
            println("  Max Single Gap    : ${String.format("%.2f", maxGapMs)} ms")
            println("  Continuity Gate   : ${if (continuityResult.isContinuityPassed) "PASSED (<= 5% missing time)" else "FAILED (> 5% missing time)"}")
            println("  Gap Distribution  :")
            gapBins.forEach { (bin, count) ->
                println(String.format("    %-20s : %d", bin, count))
            }
            println("----------------------------------------------------------------------------------------------------------")

            if (target.label.contains("Xiaomi")) {
                org.junit.Assert.assertFalse("Xiaomi fixture should fail continuity gate", continuityResult.isContinuityPassed)
            } else {
                org.junit.Assert.assertTrue("Note8 fixture should pass continuity gate", continuityResult.isContinuityPassed)
            }
        }
    }
}

package com.ronin.phoneshm.core.dsp

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.random.Random

class TransientTimeFrequencyTest {

    @Test
    fun testSafeTimestampReconstruction() {
        val sampleRateHz = 100.0f
        // Simulate a 64-bit hardware monotonic timestamp whose upper/lower 32-bit pattern
        // would represent an IEEE 754 signaling NaN (0x7F800001) if bitcast into floats
        val sNanPatternBits = 0x7F800001L
        val sessionStartNs = (sNanPatternBits shl 32) or sNanPatternBits

        val targetSampleIdx = 1234
        val expectedTimestampNs = sessionStartNs + 12_340_000_000L

        val reconstructedNs = NativeDspBridge.reconstructOnsetTimestampNs(
            sessionStartTimestampNs = sessionStartNs,
            onsetSampleIndex = targetSampleIdx.toFloat(),
            sampleRateHz = sampleRateHz
        )

        assertEquals(
            "64-bit timestamp must be reconstructed with zero precision loss and no sNaN corruption",
            expectedTimestampNs,
            reconstructedNs
        )

        // Verify pack & unpack round-trip with contiguous float layout
        val event = TransientTimeFrequencyEvent(
            onsetSampleIndex = targetSampleIdx,
            onsetTimestampNs = 0L,
            peakAccelerationG = 0.45f,
            rmsG = 0.12f,
            durationMs = 5120.0f,
            waveletEntropy = 0.92f,
            isStructuralShock = true,
            wptSubbandEnergies = FloatArray(16) { if (it == 1) 0.85f else 0.01f },
            stftTimeSteps = 5,
            stftFreqBins = 129,
            stftSpectrogram = FloatArray(5 * 129) { it * 0.001f }
        )

        val packed = NativeDspBridge.packTransientEventsRaw(listOf(event))
        // Expected length: 1 + 24 + 5 * 129 = 670 floats
        assertEquals(670, packed.size)

        val unpacked = NativeDspBridge.unpackTransientEventsRaw(packed, sessionStartNs, sampleRateHz)
        assertEquals(1, unpacked.size)
        val u = unpacked[0]
        assertEquals(targetSampleIdx, u.onsetSampleIndex)
        assertEquals(expectedTimestampNs, u.onsetTimestampNs)
        assertEquals(0.45f, u.peakAccelerationG, 1e-6f)
        assertEquals(0.12f, u.rmsG, 1e-6f)
        assertEquals(0.92f, u.waveletEntropy, 1e-6f)
        assertTrue(u.isStructuralShock)
        assertEquals(5, u.stftTimeSteps)
        assertEquals(129, u.stftFreqBins)
        assertEquals(5 * 129, u.stftSpectrogram.size)
    }

    @Test
    fun testShannonEntropyZeroEnergySubbandGuard() {
        // Case 1: All zeros -> must return 0.0f, never NaN
        val allZero = FloatArray(16) { 0.0f }
        val entropyZero = NativeDspBridge.computeShannonEntropy(allZero)
        assertFalse("Entropy of zero subbands must not be NaN", entropyZero.isNaN())
        assertEquals(0.0f, entropyZero, 1e-7f)

        // Case 2: Single subband has 100% energy, 15 subbands have 0.0f -> entropy = 0.0f
        val singleBand = FloatArray(16) { if (it == 0) 1.0f else 0.0f }
        val entropySingle = NativeDspBridge.computeShannonEntropy(singleBand)
        assertFalse(entropySingle.isNaN())
        assertEquals(0.0f, entropySingle, 1e-7f)

        // Case 3: Uniform distribution across all 16 subbands -> entropy = ln(16) ≈ 2.7725887
        val uniformBands = FloatArray(16) { 1.0f / 16.0f }
        val entropyUniform = NativeDspBridge.computeShannonEntropy(uniformBands)
        assertEquals(ln(16.0).toFloat(), entropyUniform, 1e-5f)
    }

    @Test
    fun testImpulseShockDecayLowEntropy() {
        val fs = 100.0f
        val n = 4096
        val sessionStartNs = 1_000_000_000_000L
        val signal = FloatArray(n)

        // Low-level background structural vibration at 4.0 Hz (amplitude 0.002g)
        for (i in 0 until n) {
            val t = i / fs.toDouble()
            signal[i] = (0.002 * sin(2.0 * PI * 4.0 * t)).toFloat()
        }

        // Inject a structural impulse shock at sample 1536 (t = 15.36s):
        // Damped structural mode at f0 = 3.5 Hz (within lowest db4 subbands)
        val shockStart = 1536
        val f0 = 3.5
        val zeta = 0.015
        for (i in 0 until 512) {
            val dt = i / fs.toDouble()
            val envelope = 0.25 * exp(-2.0 * PI * f0 * zeta * dt)
            signal[shockStart + i] += (envelope * sin(2.0 * PI * f0 * dt)).toFloat()
        }

        val transients = NativeDspBridge.analyzeGatedTransients(
            signal = signal,
            sessionStartTimestampNs = sessionStartNs,
            sampleRateHz = fs,
            segmentSize = 512,
            segmentOverlap = 0.50f
        )

        assertTrue("Should detect the gated structural impulse transient", transients.isNotEmpty())
        val shockEvent = transients.maxByOrNull { it.rmsG }!!

        assertTrue(
            "Structural modal decay should exhibit low wavelet entropy (< 1.2), got ${shockEvent.waveletEntropy}",
            shockEvent.waveletEntropy < 1.2f
        )
        assertTrue(
            "Narrowband low-frequency modal decay must be classified as structural shock",
            shockEvent.isStructuralShock
        )
        assertEquals(5, shockEvent.stftTimeSteps)
        assertEquals(129, shockEvent.stftFreqBins)

        // Verify peak STFT bin is near 3.5 Hz (bin index ~ 3.5 / (100/256) = 8.96 -> bin 9)
        var maxSpecPower = -1.0f
        var maxFreqBin = -1
        for (k in 0 until shockEvent.stftFreqBins) {
            val p = shockEvent.stftSpectrogram[k] // First STFT frame at shock onset
            if (p > maxSpecPower) {
                maxSpecPower = p
                maxFreqBin = k
            }
        }
        val peakStftFreqHz = maxFreqBin * (fs / 256.0f)
        assertEquals(3.5f, peakStftFreqHz, 0.6f)
    }

    @Test
    fun testBroadbandDropHighEntropy() {
        val fs = 100.0f
        val n = 4096
        val sessionStartNs = 2_000_000_000_000L
        val rng = Random(42)
        val signal = FloatArray(n)

        // Quiet ambient background
        for (i in 0 until n) {
            signal[i] = ((rng.nextDouble() - 0.5) * 0.002).toFloat()
        }

        // Inject broadband handling/drop noise burst at sample 2048 across all frequencies
        for (i in 0 until 512) {
            signal[2048 + i] = ((rng.nextDouble() - 0.5) * 0.80).toFloat()
        }

        val transients = NativeDspBridge.analyzeGatedTransients(
            signal = signal,
            sessionStartTimestampNs = sessionStartNs,
            sampleRateHz = fs,
            segmentSize = 512,
            segmentOverlap = 0.50f
        )

        assertTrue("Broadband burst should be routed to transient analyzer", transients.isNotEmpty())
        val dropEvent = transients.maxByOrNull { it.rmsG }!!

        assertTrue(
            "Broadband handling/drop noise must produce high wavelet entropy (> 2.3), got ${dropEvent.waveletEntropy}",
            dropEvent.waveletEntropy > 2.3f
        )
        assertFalse(
            "Broadband drop noise must NOT be classified as a structural modal shock",
            dropEvent.isStructuralShock
        )
    }
}

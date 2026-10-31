#include <jni.h>
#include "dsp_core.h"
#include <vector>

using namespace phoneshm::dsp;

#include "fdd_core.h"
#include "rdt_ssi_core.h"

extern "C" {

JNIEXPORT jfloatArray JNICALL
Java_com_ronin_phoneshm_core_dsp_NativeDspBridge_nativeCalculateFddRaw(
    JNIEnv* env, jobject /* this */,
    jlongArray timestamps, jfloatArray xArray, jfloatArray yArray, jfloatArray zArray,
    jfloat sampleRateHz, jint fftSize, jfloat overlapPct
) {
    jsize n = env->GetArrayLength(timestamps);
    if (n == 0) {
        return env->NewFloatArray(0);
    }
    
    if (env->GetArrayLength(xArray) != n || 
        env->GetArrayLength(yArray) != n || 
        env->GetArrayLength(zArray) != n) {
        jclass exc = env->FindClass("java/lang/IllegalArgumentException");
        env->ThrowNew(exc, "Acceleration array lengths must match timestamps length");
        return env->NewFloatArray(0);
    }
    
    jlong* ts = env->GetLongArrayElements(timestamps, nullptr);
    jfloat* x = env->GetFloatArrayElements(xArray, nullptr);
    jfloat* y = env->GetFloatArrayElements(yArray, nullptr);
    jfloat* z = env->GetFloatArrayElements(zArray, nullptr);

    std::vector<AccelerationSample> samples(n);
    for (jsize i = 0; i < n; ++i) {
        samples[i] = {ts[i], x[i], y[i], z[i]};
    }

    env->ReleaseLongArrayElements(timestamps, ts, JNI_ABORT);
    env->ReleaseFloatArrayElements(xArray, x, JNI_ABORT);
    env->ReleaseFloatArrayElements(yArray, y, JNI_ABORT);
    env->ReleaseFloatArrayElements(zArray, z, JNI_ABORT);

    FddResult result = calculateFdd(samples, sampleRateHz, fftSize, overlapPct);

    jsize freqLen = static_cast<jsize>(result.frequencies.size());
    jsize peaksLen = static_cast<jsize>(result.modes.size());

    // Phase 4 Packing layout:
    // [0]: freqLen
    // [1]: peaksLen
    // [2 .. 2+freqLen-1]: frequencies
    // [2+freqLen .. 2+2*freqLen-1]: firstSingularValues
    // [2+2*freqLen .. 2+3*freqLen-1]: coherenceXY
    // [2+3*freqLen .. 2+4*freqLen-1]: coherenceXZ
    // [2+4*freqLen .. 2+5*freqLen-1]: coherenceYZ
    // [2+5*freqLen .. 2+6*freqLen-1]: hvRatio
    // [2+6*freqLen .. end]: 10 floats per peak (freq, powerMagnitude, prominence, dampingRatio,
    //                       coherenceXY, coherenceXZ, coherenceYZ, hvRatio, azimuthDegrees, isValidHorizontalSway)
    jsize totalLen = 2 + 6 * freqLen + 10 * peaksLen;
    jfloatArray resultArray = env->NewFloatArray(totalLen);
    if (totalLen > 0) {
        std::vector<jfloat> buffer(totalLen, 0.0f);
        buffer[0] = static_cast<jfloat>(freqLen);
        buffer[1] = static_cast<jfloat>(peaksLen);

        if (freqLen > 0) {
            std::copy(result.frequencies.begin(), result.frequencies.end(), buffer.begin() + 2);
            std::copy(result.firstSingularValues.begin(), result.firstSingularValues.end(), buffer.begin() + 2 + freqLen);
            std::copy(result.coherenceXY.begin(), result.coherenceXY.end(), buffer.begin() + 2 + 2 * freqLen);
            std::copy(result.coherenceXZ.begin(), result.coherenceXZ.end(), buffer.begin() + 2 + 3 * freqLen);
            std::copy(result.coherenceYZ.begin(), result.coherenceYZ.end(), buffer.begin() + 2 + 4 * freqLen);
            std::copy(result.hvRatio.begin(), result.hvRatio.end(), buffer.begin() + 2 + 5 * freqLen);
        }

        size_t pOffset = static_cast<size_t>(2 + 6 * freqLen);
        for (jsize i = 0; i < peaksLen; ++i) {
            size_t base = pOffset + static_cast<size_t>(i) * 10;
            buffer[base + 0] = result.modes[i].frequencyHz;
            buffer[base + 1] = result.modes[i].powerMagnitude;
            buffer[base + 2] = result.modes[i].prominence;
            buffer[base + 3] = result.modes[i].dampingRatio;
            buffer[base + 4] = result.modes[i].coherenceXY;
            buffer[base + 5] = result.modes[i].coherenceXZ;
            buffer[base + 6] = result.modes[i].coherenceYZ;
            buffer[base + 7] = result.modes[i].hvRatio;
            buffer[base + 8] = result.modes[i].azimuthDegrees;
            buffer[base + 9] = result.modes[i].isValidHorizontalSway ? 1.0f : 0.0f;
        }

        env->SetFloatArrayRegion(resultArray, 0, totalLen, buffer.data());
    }

    return resultArray;
}

JNIEXPORT jfloatArray JNICALL
Java_com_ronin_phoneshm_core_dsp_NativeDspBridge_nativeWelchPsdSingleAxis(
    JNIEnv* env, jobject /* this */,
    jfloatArray signalArray, jint fftSize, jfloat overlapPct, jfloat sampleRateHz
) {
    jsize n = env->GetArrayLength(signalArray);
    jfloat* signalData = env->GetFloatArrayElements(signalArray, nullptr);

    std::vector<float> signal(signalData, signalData + n);
    env->ReleaseFloatArrayElements(signalArray, signalData, JNI_ABORT);

    std::vector<float> psd = welchPsdSingleAxis(signal, fftSize, overlapPct, sampleRateHz);

    jfloatArray resultArray = env->NewFloatArray(psd.size());
    if (psd.size() > 0) {
        env->SetFloatArrayRegion(resultArray, 0, psd.size(), psd.data());
    }
    return resultArray;
}

JNIEXPORT jobject JNICALL
Java_com_ronin_phoneshm_core_dsp_NativeDspBridge_nativeCalculateMultiAxisWelchPsd(
    JNIEnv* env, jobject /* this */,
    jlongArray timestamps, jfloatArray xArray, jfloatArray yArray, jfloatArray zArray,
    jfloat sampleRateHz, jint fftSize, jfloat overlapPct
) {
    jsize n = env->GetArrayLength(timestamps);
    if (n == 0 || 
        env->GetArrayLength(xArray) != n || 
        env->GetArrayLength(yArray) != n || 
        env->GetArrayLength(zArray) != n) {
        jclass exc = env->FindClass("java/lang/IllegalArgumentException");
        env->ThrowNew(exc, "Acceleration array lengths must match timestamps length");
        return nullptr;
    }
    
    jlong* ts = env->GetLongArrayElements(timestamps, nullptr);
    jfloat* x = env->GetFloatArrayElements(xArray, nullptr);
    jfloat* y = env->GetFloatArrayElements(yArray, nullptr);
    jfloat* z = env->GetFloatArrayElements(zArray, nullptr);

    std::vector<AccelerationSample> samples(n);
    for (jsize i = 0; i < n; ++i) {
        samples[i] = {ts[i], x[i], y[i], z[i]};
    }

    env->ReleaseLongArrayElements(timestamps, ts, JNI_ABORT);
    env->ReleaseFloatArrayElements(xArray, x, JNI_ABORT);
    env->ReleaseFloatArrayElements(yArray, y, JNI_ABORT);
    env->ReleaseFloatArrayElements(zArray, z, JNI_ABORT);

    WelchPsdParams params;
    params.fftSize = fftSize;
    params.overlapPercentage = overlapPct;

    MultiAxisSpectrumResult result = calculateMultiAxisWelchPsd(samples, sampleRateHz, params);

    // Get NativeWelchResult class and constructor
    jclass resultClass = env->FindClass("com/ronin/phoneshm/core/dsp/NativeWelchResult");
    jmethodID ctor = env->GetMethodID(resultClass, "<init>", "(I[F[F[F[F[F)V");

    int segmentCount = result.output.actualSegmentCount;
    
    // We pass frequencies, psdX, psdY, psdZ, psdMag
    jsize freqLen = result.psdX.frequencies.size();
    
    jfloatArray freqs = env->NewFloatArray(freqLen);
    jfloatArray px = env->NewFloatArray(freqLen);
    jfloatArray py = env->NewFloatArray(freqLen);
    jfloatArray pz = env->NewFloatArray(freqLen);
    jfloatArray pmag = env->NewFloatArray(freqLen);

    if (freqLen > 0) {
        env->SetFloatArrayRegion(freqs, 0, freqLen, result.psdX.frequencies.data());
        env->SetFloatArrayRegion(px, 0, freqLen, result.psdX.powerSpectralDensity.data());
        env->SetFloatArrayRegion(py, 0, freqLen, result.psdY.powerSpectralDensity.data());
        env->SetFloatArrayRegion(pz, 0, freqLen, result.psdZ.powerSpectralDensity.data());
        env->SetFloatArrayRegion(pmag, 0, freqLen, result.psdMagnitude.powerSpectralDensity.data());
    }

    jobject resultObj = env->NewObject(resultClass, ctor, segmentCount, freqs, px, py, pz, pmag);

    return resultObj;
}

JNIEXPORT jfloat JNICALL
Java_com_ronin_phoneshm_core_dsp_NativeDspBridge_nativeStreamingRmsLevel(
    JNIEnv* /* env */, jobject /* this */,
    jfloat x, jfloat y, jfloat z, jfloat prevRms
) {
    AccelerationSample sample = {0, x, y, z};
    return streamingRmsLevel(sample, prevRms);
}

JNIEXPORT jfloatArray JNICALL
Java_com_ronin_phoneshm_core_dsp_NativeDspBridge_nativeCalculateRdtSsi(
    JNIEnv* env, jobject /* this */,
    jfloatArray xArray, jfloatArray yArray, jfloatArray zArray,
    jfloat fs, jfloat minHz, jfloat maxHz, jint maxOrder, jfloat rdsDurationSec
) {
    jsize len = env->GetArrayLength(xArray);
    if (len == 0 || 
        env->GetArrayLength(yArray) != len || 
        env->GetArrayLength(zArray) != len) {
        jclass exc = env->FindClass("java/lang/IllegalArgumentException");
        env->ThrowNew(exc, "Acceleration array lengths must match and be greater than 0");
        return env->NewFloatArray(0);
    }
    
    jfloat* x = env->GetFloatArrayElements(xArray, nullptr);
    jfloat* y = env->GetFloatArrayElements(yArray, nullptr);
    jfloat* z = env->GetFloatArrayElements(zArray, nullptr);

    std::vector<phoneshm::dsp::RdtSsiPole> poles = phoneshm::dsp::calculateRdtSsi(
        x, y, z, len, fs, minHz, maxHz, maxOrder, rdsDurationSec
    );

    env->ReleaseFloatArrayElements(xArray, x, JNI_ABORT);
    env->ReleaseFloatArrayElements(yArray, y, JNI_ABORT);
    env->ReleaseFloatArrayElements(zArray, z, JNI_ABORT);

    jfloatArray resultArray = env->NewFloatArray(poles.size() * 3);
    if (poles.size() > 0) {
        std::vector<jfloat> flattened(poles.size() * 3);
        for (size_t i = 0; i < poles.size(); ++i) {
            flattened[i * 3 + 0] = poles[i].freq;
            flattened[i * 3 + 1] = poles[i].damp;
            flattened[i * 3 + 2] = static_cast<jfloat>(poles[i].order);
        }
        env->SetFloatArrayRegion(resultArray, 0, poles.size() * 3, flattened.data());
    }
    return resultArray;
}

JNIEXPORT jfloatArray JNICALL
Java_com_ronin_phoneshm_core_dsp_NativeDspBridge_nativeAnalyzeGatedTransientsRaw(
    JNIEnv* env, jobject /* this */,
    jfloatArray signalArray, jfloat sampleRateHz, jint segmentSize, jfloat segmentOverlap, jbyteArray gapMaskArray
) {
    if (signalArray == nullptr) {
        return env->NewFloatArray(0);
    }
    jsize n = env->GetArrayLength(signalArray);
    if (n <= 0) {
        return env->NewFloatArray(0);
    }

    jfloat* sigPtr = env->GetFloatArrayElements(signalArray, nullptr);
    std::vector<float> signal(sigPtr, sigPtr + n);
    env->ReleaseFloatArrayElements(signalArray, sigPtr, JNI_ABORT);

    std::vector<uint8_t> gapMask;
    if (gapMaskArray != nullptr) {
        jsize mLen = env->GetArrayLength(gapMaskArray);
        if (mLen > 0) {
            jbyte* maskPtr = env->GetByteArrayElements(gapMaskArray, nullptr);
            gapMask.resize(mLen);
            for (jsize i = 0; i < mLen; ++i) {
                gapMask[i] = static_cast<uint8_t>(maskPtr[i]);
            }
            env->ReleaseByteArrayElements(gapMaskArray, maskPtr, JNI_ABORT);
        }
    }

    std::vector<TransientEventAnalysis> events = analyzeGatedTransients(
        signal, sampleRateHz, segmentSize, segmentOverlap, gapMask
    );

    size_t kCount = std::min<size_t>(5, events.size());
    size_t totalFloats = 1;
    for (size_t k = 0; k < kCount; ++k) {
        size_t specLen = static_cast<size_t>(events[k].stftTimeSteps) * static_cast<size_t>(events[k].stftFreqBins);
        totalFloats += 24 + specLen;
    }

    std::vector<jfloat> packed(totalFloats, 0.0f);
    packed[0] = static_cast<jfloat>(kCount);

    size_t offset = 1;
    for (size_t k = 0; k < kCount; ++k) {
        const auto& ev = events[k];
        packed[offset + 0] = static_cast<jfloat>(ev.onsetSampleIndex);
        packed[offset + 1] = ev.peakAccelerationG;
        packed[offset + 2] = ev.rmsG;
        packed[offset + 3] = ev.durationMs;
        packed[offset + 4] = ev.waveletEntropy;
        packed[offset + 5] = ev.isStructuralShock ? 1.0f : 0.0f;
        for (int b = 0; b < 16; ++b) {
            packed[offset + 6 + b] = ev.wptSubbandEnergies[b];
        }
        packed[offset + 22] = static_cast<jfloat>(ev.stftTimeSteps);
        packed[offset + 23] = static_cast<jfloat>(ev.stftFreqBins);

        size_t specLen = static_cast<size_t>(ev.stftTimeSteps) * static_cast<size_t>(ev.stftFreqBins);
        if (specLen > 0 && ev.stftSpectrogram.size() >= specLen) {
            std::copy(
                ev.stftSpectrogram.begin(),
                ev.stftSpectrogram.begin() + specLen,
                packed.begin() + offset + 24
            );
        }
        offset += 24 + specLen;
    }

    jfloatArray outArray = env->NewFloatArray(static_cast<jsize>(totalFloats));
    env->SetFloatArrayRegion(outArray, 0, static_cast<jsize>(totalFloats), packed.data());
    return outArray;
}

} // extern "C"

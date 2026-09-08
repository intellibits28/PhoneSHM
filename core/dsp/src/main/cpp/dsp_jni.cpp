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

    jsize freqLen = result.frequencies.size();
    jsize peaksLen = result.modes.size();

    // Packing layout:
    // [0]: freqLen
    // [1]: peaksLen
    // [2 .. 2+freqLen-1]: frequencies
    // [2+freqLen .. 2+2*freqLen-1]: firstSingularValues
    // [2+2*freqLen .. end]: 4 floats per peak (freq, powerMagnitude, prominence, dampingRatio)
    jsize totalLen = 2 + 2 * freqLen + 4 * peaksLen;
    jfloatArray resultArray = env->NewFloatArray(totalLen);
    if (totalLen > 0) {
        std::vector<jfloat> buffer(totalLen);
        buffer[0] = static_cast<jfloat>(freqLen);
        buffer[1] = static_cast<jfloat>(peaksLen);

        if (freqLen > 0) {
            std::copy(result.frequencies.begin(), result.frequencies.end(), buffer.begin() + 2);
            std::copy(result.firstSingularValues.begin(), result.firstSingularValues.end(), buffer.begin() + 2 + freqLen);
        }

        size_t pOffset = 2 + 2 * freqLen;
        for (size_t i = 0; i < peaksLen; ++i) {
            buffer[pOffset + i * 4 + 0] = result.modes[i].frequencyHz;
            buffer[pOffset + i * 4 + 1] = result.modes[i].powerMagnitude;
            buffer[pOffset + i * 4 + 2] = result.modes[i].prominence;
            buffer[pOffset + i * 4 + 3] = result.modes[i].dampingRatio;
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

} // extern "C"

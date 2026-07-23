#include <jni.h>
#include "dsp_core.h"
#include <vector>

using namespace phoneshm::dsp;

extern "C" {

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

} // extern "C"

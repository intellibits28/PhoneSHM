#include <jni.h>
#include "dsp_core.h"
#include <vector>

using namespace phoneshm::dsp;

#include "fdd_core.h"
#include "rdt_ssi_core.h"

extern "C" {

JNIEXPORT jobject JNICALL
Java_com_ronin_phoneshm_core_dsp_NativeDspBridge_nativeCalculateFdd(
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

    FddResult result = calculateFdd(samples, sampleRateHz, fftSize, overlapPct);

    jclass resultClass = env->FindClass("com/ronin/phoneshm/core/dsp/NativeFddResult");
    jmethodID ctor = env->GetMethodID(resultClass, "<init>", "([F[F[F[F[F[F)V");

    jsize freqLen = result.frequencies.size();
    jfloatArray freqs = env->NewFloatArray(freqLen);
    jfloatArray sv = env->NewFloatArray(freqLen);

    if (freqLen > 0) {
        env->SetFloatArrayRegion(freqs, 0, freqLen, result.frequencies.data());
        env->SetFloatArrayRegion(sv, 0, freqLen, result.firstSingularValues.data());
    }
    
    jsize peaksLen = result.modes.size();
    jfloatArray pFreqs = env->NewFloatArray(peaksLen);
    jfloatArray pMags = env->NewFloatArray(peaksLen);
    jfloatArray pProms = env->NewFloatArray(peaksLen);
    jfloatArray pDamps = env->NewFloatArray(peaksLen);
    
    if (peaksLen > 0) {
        std::vector<float> pF(peaksLen), pM(peaksLen), pP(peaksLen), pD(peaksLen);
        for(size_t i = 0; i < peaksLen; i++) {
            pF[i] = result.modes[i].frequencyHz;
            pM[i] = result.modes[i].powerMagnitude;
            pP[i] = result.modes[i].prominence;
            pD[i] = result.modes[i].dampingRatio;
        }
        env->SetFloatArrayRegion(pFreqs, 0, peaksLen, pF.data());
        env->SetFloatArrayRegion(pMags, 0, peaksLen, pM.data());
        env->SetFloatArrayRegion(pProms, 0, peaksLen, pP.data());
        env->SetFloatArrayRegion(pDamps, 0, peaksLen, pD.data());
    }

    return env->NewObject(resultClass, ctor, freqs, sv, pFreqs, pMags, pProms, pDamps);
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

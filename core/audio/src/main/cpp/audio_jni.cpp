#include <jni.h>
#include <vector>
#include <string>
#include "WptFeatureExtractor.h"

extern "C" JNIEXPORT jobject JNICALL
Java_com_ronin_phoneshm_core_audio_WptFeatureExtractor_extractNodeEnergiesNative(
        JNIEnv* env,
        jobject /* this */,
        jfloatArray rawPcm,
        jint nativeSampleRate,
        jint depth) {
    
    // Extract native array
    jsize length = env->GetArrayLength(rawPcm);
    jfloat* pcmData = env->GetFloatArrayElements(rawPcm, nullptr);
    std::vector<float> pcmVector(pcmData, pcmData + length);
    env->ReleaseFloatArrayElements(rawPcm, pcmData, JNI_ABORT);
    
    // Initialize extractor and process
    phoneshm::audio::WptFeatureExtractor extractor(depth);
    phoneshm::audio::WptFeatures features = extractor.extractNodeEnergies(pcmVector, nativeSampleRate);
    
    // Convert energies to Java float array
    jfloatArray jEnergies = env->NewFloatArray(features.nodeEnergies.size());
    env->SetFloatArrayRegion(jEnergies, 0, features.nodeEnergies.size(), features.nodeEnergies.data());
    
    // Create Java WptFeatures object
    jclass resultClass = env->FindClass("com/ronin/phoneshm/core/audio/WptFeatures");
    jmethodID constructor = env->GetMethodID(resultClass, "<init>", "([FLjava/lang/String;D)V");
    jstring jLabel = env->NewStringUTF(features.eventLabel.c_str());
    
    jobject resultObj = env->NewObject(resultClass, constructor, jEnergies, jLabel, features.confidence);
    
    return resultObj;
}

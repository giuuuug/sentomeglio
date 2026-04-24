#include <jni.h>
#include <string>
#include "AudioEngine.h"

static AudioEngine engine;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_sentomeglio_app_NativeBridge_startAudioEngine(JNIEnv *env, jobject /* this */, jint inputId, jint outputId, jstring modelPath, jint nFft, jint hopLength, jint winLength) {
    const char *nativeString = env->GetStringUTFChars(modelPath, 0);
    std::string path(nativeString);
    env->ReleaseStringUTFChars(modelPath, nativeString);
    return engine.start(inputId, outputId, path, nFft, hopLength, winLength) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_sentomeglio_app_NativeBridge_stopAudioEngine(JNIEnv *env, jobject /* this */) {
    engine.stop();
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_sentomeglio_app_NativeBridge_getInferenceLatencyMs(JNIEnv *env, jobject /* this */) {
    return engine.getInferenceLatencyMs();
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_sentomeglio_app_NativeBridge_getDspLatencyMs(JNIEnv *env, jobject /* this */) {
    return engine.getDspLatencyMs();
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_sentomeglio_app_NativeBridge_getHwLatencyMs(JNIEnv *env, jobject /* this */) {
    return engine.getHwLatencyMs();
}

extern "C" JNIEXPORT void JNICALL
Java_com_sentomeglio_app_NativeBridge_getSpectrograms(JNIEnv *env, jobject /* this */, jfloatArray noisyArray, jfloatArray denArray) {
    std::vector<float> noisyDb, denDb;
    engine.getSpectrograms(noisyDb, denDb);

    if (!noisyDb.empty() && !denDb.empty()) {
        jsize len = env->GetArrayLength(noisyArray);
        if (len >= (jsize)noisyDb.size()) {
            env->SetFloatArrayRegion(noisyArray, 0, noisyDb.size(), noisyDb.data());
            env->SetFloatArrayRegion(denArray, 0, denDb.size(), denDb.data());
        }
    }
}

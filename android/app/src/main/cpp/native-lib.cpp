#include "AudioEngine.h"
#include <jni.h>
#include <string>

static AudioEngine engine;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_sentomeglio_app_NativeBridge_startAudioEngine(
    JNIEnv *env, jobject /* this */, jint inputId, jint outputId,
    jstring modelPath, jint nFft, jint hopLength, jint winLength)
{
  const char *nativeString = env->GetStringUTFChars(modelPath, 0);
  std::string path(nativeString);
  env->ReleaseStringUTFChars(modelPath, nativeString);
  return engine.start(inputId, outputId, path, nFft, hopLength, winLength)
             ? JNI_TRUE
             : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_sentomeglio_app_NativeBridge_stopAudioEngine(JNIEnv *env,
                                                      jobject /* this */)
{
  engine.stop();
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_sentomeglio_app_NativeBridge_getInferenceLatencyMs(
    JNIEnv *env, jobject /* this */)
{
  return engine.getInferenceLatencyMs();
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_sentomeglio_app_NativeBridge_getDspLatencyMs(JNIEnv *env,
                                                      jobject /* this */)
{
  return engine.getDspLatencyMs();
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_sentomeglio_app_NativeBridge_getHwLatencyMs(JNIEnv *env,
                                                     jobject /* this */)
{
  return engine.getHwLatencyMs();
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_sentomeglio_app_NativeBridge_getInputLatencyMs(JNIEnv *env, jobject)
{
  return engine.getInputLatencyMs();
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_sentomeglio_app_NativeBridge_getOutputLatencyMs(JNIEnv *env, jobject)
{
  return engine.getOutputLatencyMs();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_sentomeglio_app_NativeBridge_getXRunCount(JNIEnv *env, jobject)
{
  return engine.getXRunCount();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_sentomeglio_app_NativeBridge_getBufferSizeFrames(JNIEnv *env, jobject)
{
  return engine.getBufferSizeFrames();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_sentomeglio_app_NativeBridge_getBurstSizeFrames(JNIEnv *env, jobject)
{
  return engine.getBurstSizeFrames();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_sentomeglio_app_NativeBridge_getSampleRateHz(JNIEnv *env, jobject)
{
  return engine.getSampleRateHz();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_sentomeglio_app_NativeBridge_getSharingMode(JNIEnv *env, jobject)
{
  return engine.getSharingMode();
}

extern "C" JNIEXPORT void JNICALL
Java_com_sentomeglio_app_NativeBridge_setRecording(JNIEnv *env, jobject /* this */,
                                                   jboolean enabled,
                                                   jstring noisyPath,
                                                   jstring denoisedPath)
{
  const char *nNoisy    = env->GetStringUTFChars(noisyPath,    0);
  const char *nDenoised = env->GetStringUTFChars(denoisedPath, 0);
  engine.setRecording(enabled == JNI_TRUE, std::string(nNoisy), std::string(nDenoised));
  env->ReleaseStringUTFChars(noisyPath,    nNoisy);
  env->ReleaseStringUTFChars(denoisedPath, nDenoised);
}

extern "C" JNIEXPORT void JNICALL
Java_com_sentomeglio_app_NativeBridge_getSpectrograms(JNIEnv *env,
                                                      jobject /* this */,
                                                      jfloatArray noisyArray,
                                                      jfloatArray denArray)
{
  std::vector<float> noisyDb, denDb;
  engine.getSpectrograms(noisyDb, denDb);

  if (!noisyDb.empty() && !denDb.empty())
  {
    jsize len = env->GetArrayLength(noisyArray);
    if (len >= (jsize)noisyDb.size())
    {
      env->SetFloatArrayRegion(noisyArray, 0, noisyDb.size(), noisyDb.data());
      env->SetFloatArrayRegion(denArray, 0, denDb.size(), denDb.data());
    }
  }
}

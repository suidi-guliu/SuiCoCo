#include <jni.h>
#include <string>
#include <android/log.h>
#define LOG_TAG "suicoco-proot"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jstring JNICALL
Java_com_suicoco_nativelib_proot_ProotBridge_nativeProotVersion(JNIEnv *env, jobject) {
    LOGI("proot bridge loaded");
    return env->NewStringUTF("proot-arm64-24.04");
}

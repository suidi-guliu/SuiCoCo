#include <jni.h>
#include <android/log.h>
#define LOG_TAG "suicoco-pty"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jboolean JNICALL
Java_com_suicoco_nativelib_pty_PtyBridge_nativeAvailable(JNIEnv *, jobject) {
    LOGI("pty bridge loaded");
    return JNI_TRUE;
}

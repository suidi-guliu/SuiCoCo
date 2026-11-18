#include <jni.h>
#include <android/log.h>

#include <fcntl.h>
#include <unistd.h>

#define LOG_TAG "suicoco-pty"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {
// 探测内核 PTY 多路复用设备是否可用。真机上 /dev/ptmx 由内核提供；
// 若设备缺失或权限受限，则返回 false，供上层决定是否回退到 JVM 进程执行路径。
bool probe_ptmx_available() {
    int fd = ::open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    if (fd < 0) {
        LOGI("/dev/ptmx unavailable, native PTY path disabled");
        return false;
    }
    ::close(fd);
    LOGI("/dev/ptmx available, native PTY path enabled");
    return true;
}
}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_suicoco_nativelib_pty_PtyBridge_nativeAvailable(JNIEnv*, jobject) {
    return probe_ptmx_available() ? JNI_TRUE : JNI_FALSE;
}

#include <jni.h>
#include <android/log.h>

#include <cerrno>
#include <csignal>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#include <fcntl.h>
#include <poll.h>
#include <pty.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#define LOG_TAG "suicoco-pty"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// 读取轮询：短超时避免 JNI 线程永久阻塞，由上层按需循环调用。
constexpr int kReadTimeoutMs = 50;
constexpr int kReadChunk = 16 * 1024;
constexpr jlong kInvalidSession = -1;

struct Session {
    pid_t pid = -1;
    int masterFd = -1;
    bool reaped = false;
    int exitCode = -1;
};

std::mutex gMutex;
std::unordered_map<jlong, std::shared_ptr<Session>> gSessions;
jlong gNextId = 1;

std::shared_ptr<Session> findSession(jlong id) {
    std::lock_guard<std::mutex> lock(gMutex);
    auto it = gSessions.find(id);
    return it == gSessions.end() ? nullptr : it->second;
}

jlong registerSession(const std::shared_ptr<Session>& s) {
    std::lock_guard<std::mutex> lock(gMutex);
    const jlong id = gNextId++;
    gSessions[id] = s;
    return id;
}

// jobjectArray -> vector<string>；任一元素取不到即整体失败（fail-closed）。
bool readArgv(JNIEnv* env, jobjectArray arr, std::vector<std::string>& out) {
    if (arr == nullptr) return false;
    const jsize n = env->GetArrayLength(arr);
    out.clear();
    out.reserve(static_cast<size_t>(n));
    for (jsize i = 0; i < n; ++i) {
        jstring js = static_cast<jstring>(env->GetObjectArrayElement(arr, i));
        if (js == nullptr) { out.clear(); return false; }
        const char* c = env->GetStringUTFChars(js, nullptr);
        if (c == nullptr) { env->DeleteLocalRef(js); out.clear(); return false; }
        out.emplace_back(c);
        env->ReleaseStringUTFChars(js, c);
        env->DeleteLocalRef(js);
    }
    return !out.empty();
}

int decodeExitStatus(int status) {
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_suicoco_nativelib_pty_PtyBridge_nativeAvailable(JNIEnv*, jobject) {
    int fd = ::open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    if (fd < 0) {
        LOGI("/dev/ptmx unavailable, native PTY path disabled");
        return JNI_FALSE;
    }
    ::close(fd);
    LOGI("/dev/ptmx available, native PTY path enabled");
    return JNI_TRUE;
}

// 打开伪终端会话：forkpty + execvp(argv[0], argv)。
// 只在子进程执行调用方传入的 argv，本桥不自行提权、不注入任何命令，
// 沿用「只执行调用方 argv」的安全不变量；无 host root 约束由上层 ProotManager 保证。
JNIEXPORT jlong JNICALL
Java_com_suicoco_nativelib_pty_PtyBridge_nativeOpen(
        JNIEnv* env, jobject, jint cols, jint rows, jobjectArray argv) {
    std::vector<std::string> args;
    if (!readArgv(env, argv, args)) {
        LOGE("nativeOpen: empty argv");
        return kInvalidSession;
    }
    std::vector<char*> cargs;
    cargs.reserve(args.size() + 1);
    for (auto& a : args) cargs.push_back(const_cast<char*>(a.c_str()));
    cargs.push_back(nullptr);

    struct winsize ws {};
    ws.ws_col = static_cast<unsigned short>(cols > 0 ? cols : 80);
    ws.ws_row = static_cast<unsigned short>(rows > 0 ? rows : 24);

    int master = -1;
    pid_t pid = ::forkpty(&master, nullptr, nullptr, &ws);
    if (pid < 0) {
        LOGE("forkpty failed: %s", std::strerror(errno));
        return kInvalidSession;
    }
    if (pid == 0) {
        // 子进程：仅设置基础终端环境后 execvp；失败则 _exit(127)，绝不回到父逻辑。
        ::setenv("TERM", "xterm-256color", 1);
        ::execvp(cargs[0], cargs.data());
        LOGE("execvp failed: %s", std::strerror(errno));
        ::_exit(127);
    }

    // 父进程：master 设为非阻塞，交由上层循环读取，避免 JNI 线程被永久 hang。
    int flags = ::fcntl(master, F_GETFL, 0);
    if (flags >= 0) ::fcntl(master, F_SETFL, flags | O_NONBLOCK);

    auto session = std::make_shared<Session>();
    session->pid = pid;
    session->masterFd = master;
    const jlong id = registerSession(session);
    LOGI("nativeOpen: id=%lld pid=%d master=%d",
         static_cast<long long>(id), pid, master);
    return id;
}

// 向 master 写入输入字节，返回实际写入数；-1 表示会话无效或写失败。
JNIEXPORT jint JNICALL
Java_com_suicoco_nativelib_pty_PtyBridge_nativeWrite(
        JNIEnv* env, jobject, jlong sid, jbyteArray data) {
    auto s = findSession(sid);
    if (!s || s->masterFd < 0) return -1;
    const jsize n = env->GetArrayLength(data);
    if (n <= 0) return 0;
    std::vector<jbyte> buf(static_cast<size_t>(n));
    env->GetByteArrayRegion(data, 0, n, buf.data());
    const ssize_t written = ::write(s->masterFd, buf.data(), static_cast<size_t>(n));
    return written < 0 ? -1 : static_cast<jint>(written);
}

// 从 master 非阻塞读取一次输出；无数据或子进程已关闭从端时返回空数组。
JNIEXPORT jbyteArray JNICALL
Java_com_suicoco_nativelib_pty_PtyBridge_nativeRead(JNIEnv* env, jobject, jlong sid) {
    auto s = findSession(sid);
    if (!s || s->masterFd < 0) return env->NewByteArray(0);
    struct pollfd pfd {};
    pfd.fd = s->masterFd;
    pfd.events = POLLIN;
    const int pr = ::poll(&pfd, 1, kReadTimeoutMs);
    if (pr <= 0 || !(pfd.revents & POLLIN)) return env->NewByteArray(0);
    std::vector<char> buf(kReadChunk);
    const ssize_t n = ::read(s->masterFd, buf.data(), buf.size());
    if (n <= 0) return env->NewByteArray(0);  // EIO：从端已关闭
    jbyteArray out = env->NewByteArray(static_cast<jsize>(n));
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(n),
                            reinterpret_cast<const jbyte*>(buf.data()));
    return out;
}

// 调整伪终端窗口大小（TIOCSWINSZ），供终端面板尺寸变化同步。
JNIEXPORT jboolean JNICALL
Java_com_suicoco_nativelib_pty_PtyBridge_nativeResize(
        JNIEnv*, jobject, jlong sid, jint cols, jint rows) {
    auto s = findSession(sid);
    if (!s || s->masterFd < 0) return JNI_FALSE;
    struct winsize ws {};
    ws.ws_col = static_cast<unsigned short>(cols > 0 ? cols : 80);
    ws.ws_row = static_cast<unsigned short>(rows > 0 ? rows : 24);
    return ::ioctl(s->masterFd, TIOCSWINSZ, &ws) == 0 ? JNI_TRUE : JNI_FALSE;
}

// 非阻塞探测会话是否仍存活；顺带回收刚退出的子进程并登记退出码。
JNIEXPORT jboolean JNICALL
Java_com_suicoco_nativelib_pty_PtyBridge_nativeIsAlive(JNIEnv*, jobject, jlong sid) {
    auto s = findSession(sid);
    if (!s || s->reaped) return JNI_FALSE;
    int status = 0;
    const pid_t r = ::waitpid(s->pid, &status, WNOHANG);
    if (r == 0) return JNI_TRUE;  // 仍在运行
    if (r == s->pid) {
        s->reaped = true;
        s->exitCode = decodeExitStatus(status);
    }
    return JNI_FALSE;
}

// 阻塞等待会话结束并返回退出码；已回收则直接返回缓存值。
JNIEXPORT jint JNICALL
Java_com_suicoco_nativelib_pty_PtyBridge_nativeWait(JNIEnv*, jobject, jlong sid) {
    auto s = findSession(sid);
    if (!s) return -1;
    if (s->reaped) return s->exitCode;
    int status = 0;
    pid_t r;
    do {
        r = ::waitpid(s->pid, &status, 0);
    } while (r < 0 && errno == EINTR);
    if (r == s->pid) {
        s->reaped = true;
        s->exitCode = decodeExitStatus(status);
        return s->exitCode;
    }
    return -1;
}

// 关闭会话：关 master、终止可能残留的子进程、注销表项，杜绝 fd 泄漏与僵尸进程。
JNIEXPORT jboolean JNICALL
Java_com_suicoco_nativelib_pty_PtyBridge_nativeClose(JNIEnv*, jobject, jlong sid) {
    std::shared_ptr<Session> s;
    {
        std::lock_guard<std::mutex> lock(gMutex);
        auto it = gSessions.find(sid);
        if (it == gSessions.end()) return JNI_FALSE;
        s = it->second;
        gSessions.erase(it);
    }
    if (s->masterFd >= 0) {
        ::close(s->masterFd);
        s->masterFd = -1;
    }
    if (!s->reaped && s->pid > 0) {
        ::kill(s->pid, SIGHUP);
        int status = 0;
        const pid_t r = ::waitpid(s->pid, &status, WNOHANG);
        if (r == 0) {  // 未立即退出，补 SIGKILL 后回收，避免僵尸
            ::kill(s->pid, SIGKILL);
            (void)::waitpid(s->pid, &status, 0);
        }
        s->reaped = true;
    }
    LOGI("nativeClose: id=%lld", static_cast<long long>(sid));
    return JNI_TRUE;
}

}  // extern "C"

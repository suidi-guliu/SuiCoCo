#include <jni.h>
#include <android/log.h>

#include <cstdint>
#include <cstdio>
#include <cstring>

#define LOG_TAG "suicoco-proot"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// ELF64 文件头中本校验所需的字段（按小端布局手工解析，避免依赖 <elf.h> 的平台差异）。
struct Elf64HeaderProbe {
    uint8_t  e_ident[16];
    uint16_t e_type;
    uint16_t e_machine;
    uint32_t e_version;
};

constexpr uint16_t kEmAarch64 = 183;   // EM_AARCH64
constexpr uint8_t  kElfClass64 = 2;    // ELFCLASS64
constexpr uint8_t  kElfDataLsb = 1;    // ELFDATA2LSB (little-endian)
constexpr uint8_t  kElfMagic[4] = {0x7F, 'E', 'L', 'F'};

// 校验给定路径是否为合法的 arm64 (AArch64) ELF64 文件。
// 判定条件：magic 正确 + ELFCLASS64 + 小端 + e_machine == EM_AARCH64。
bool is_valid_arm64_elf(const char* path) {
    if (path == nullptr || *path == '\0') return false;
    FILE* fp = std::fopen(path, "rb");
    if (fp == nullptr) {
        LOGE("open failed: %s", path);
        return false;
    }
    Elf64HeaderProbe header{};
    const size_t n = std::fread(&header, 1, sizeof(header), fp);
    std::fclose(fp);
    if (n < sizeof(header)) {
        LOGE("too short to be ELF64: %s", path);
        return false;
    }
    if (std::memcmp(header.e_ident, kElfMagic, 4) != 0) {
        LOGE("bad ELF magic: %s", path);
        return false;
    }
    if (header.e_ident[4] != kElfClass64) {
        LOGE("not ELFCLASS64: %s", path);
        return false;
    }
    if (header.e_ident[5] != kElfDataLsb) {
        LOGE("not little-endian: %s", path);
        return false;
    }
    if (header.e_machine != kEmAarch64) {
        LOGE("not AArch64 (e_machine=%u): %s", header.e_machine, path);
        return false;
    }
    LOGI("valid arm64 ELF: %s", path);
    return true;
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_suicoco_nativelib_proot_ProotBridge_nativeProotVersion(JNIEnv* env, jobject) {
    // 返回桥实现版本标识。真实 proot 二进制由上层 ProotManager 通过
    // SUICOCO_PROOT 注入或 app nativeLibraryDir 解析，本方法不再伪造二进制版本。
    const char* version = "proot-bridge/arm64/elf-verify/v1";
    LOGI("proot bridge: %s", version);
    return env->NewStringUTF(version);
}

// 校验注入的 proot/libproot.so 绝对路径是否为合法 arm64 ELF（随包自检）。
JNIEXPORT jboolean JNICALL
Java_com_suicoco_nativelib_proot_ProotBridge_nativeValidateElf(JNIEnv* env, jobject, jstring jpath) {
    if (jpath == nullptr) return JNI_FALSE;
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    if (path == nullptr) return JNI_FALSE;
    const bool ok = is_valid_arm64_elf(path);
    env->ReleaseStringUTFChars(jpath, path);
    return ok ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"

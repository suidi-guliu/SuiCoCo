package com.suicoco.nativelib.proot

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * proot native 桥 —— arm64 ELF 头校验。
 *
 * native 库随 APK 打包（app 依赖 :native:proot），正常环境直接走 JNI。
 * 仅在加载失败（如纯 JVM 测试环境）时，[safeValidateArm64Elf] 提供 JVM 侧等价降级，
 * 避免上层自检因 UnsatisfiedLinkError 崩溃。
 */
object ProotBridge {
    private val nativeOk: Boolean = try {
        System.loadLibrary("suicoco_proot")
        true
    } catch (t: Throwable) {
        false
    }

    /** 桥实现版本标识（向后兼容原有 public API）。 */
    external fun nativeProotVersion(): String

    /** 校验注入的 proot/libproot.so 是否为合法 arm64 ELF（随包自检）。 */
    external fun nativeValidateElf(path: String): Boolean

    /** 带降级的校验入口：native 不可用时自动用 JVM 侧等价实现。 */
    fun safeValidateArm64Elf(path: String): Boolean =
        if (nativeOk) {
            try { nativeValidateElf(path) } catch (t: Throwable) { jvmValidateArm64Elf(path) }
        } else {
            jvmValidateArm64Elf(path)
        }

    /** 带降级的版本入口。 */
    fun safeVersion(): String =
        if (nativeOk) {
            try { nativeProotVersion() } catch (t: Throwable) { FALLBACK_VERSION }
        } else {
            FALLBACK_VERSION
        }

    private const val FALLBACK_VERSION = "proot-bridge/jvm-fallback/elf-verify/v1"

    /** JVM 侧等价校验：读取前 20 字节，检查 ELF64 magic + 小端 + e_machine=183(AArch64)。 */
    private fun jvmValidateArm64Elf(path: String): Boolean {
        val file = File(path)
        if (!file.isFile) return false
        return try {
            val buf = ByteArray(20)
            file.inputStream().use { input ->
                var off = 0
                while (off < buf.size) {
                    val n = input.read(buf, off, buf.size - off)
                    if (n < 0) return false
                    off += n
                }
            }
            val magicOk = (buf[0].toInt() and 0xFF) == 0x7F &&
                buf[1] == 'E'.code.toByte() && buf[2] == 'L'.code.toByte() && buf[3] == 'F'.code.toByte()
            val elfClass64 = (buf[4].toInt() and 0xFF) == 2
            val littleEndian = (buf[5].toInt() and 0xFF) == 1
            val eMachine = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).getShort(18).toInt() and 0xFFFF
            magicOk && elfClass64 && littleEndian && eMachine == 183
        } catch (t: Throwable) {
            false
        }
    }
}

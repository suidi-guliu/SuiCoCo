package com.suicoco.nativelib.pty

/**
 * PTY native 桥 —— arm64 上基于 forkpty 的真实伪终端会话链。
 *
 * native 库随 APK 打包（app 依赖 :native:pty）。每条会话只执行调用方传入的 argv，
 * 本桥不自行提权、不注入命令；no-root 约束由上层 ProotManager 在调用前保证。
 * 仅在加载失败（如纯 JVM 测试环境）时提供 JVM 侧降级，避免上层自检因
 * UnsatisfiedLinkError 崩溃。会话的创建/读写/调整大小/存活探测/等待/关闭分别
 * 对应 8 个 JNI 方法（含 nativeAvailable）。
 */
object PtyBridge {
    private val nativeOk: Boolean = try {
        System.loadLibrary("suicoco_pty")
        true
    } catch (t: Throwable) {
        false
    }

    /** 底层 PTY 设备是否可用；native 不可用时按 JVM 侧 /dev/ptmx 探测降级。 */
    external fun nativeAvailable(): Boolean

    /** 打开会话：forkpty + execvp(argv)，成功返回正 sessionId，失败返回 -1。 */
    external fun nativeOpen(cols: Int, rows: Int, argv: Array<String>): Long

    /** 向会话 master 写入输入，返回写入字节数；失败返回 -1。 */
    external fun nativeWrite(sessionId: Long, data: ByteArray): Int

    /** 非阻塞读取一次输出；无数据返回空数组。 */
    external fun nativeRead(sessionId: Long): ByteArray

    /** 调整窗口大小（TIOCSWINSZ）。 */
    external fun nativeResize(sessionId: Long, cols: Int, rows: Int): Boolean

    /** 非阻塞探测会话是否仍存活。 */
    external fun nativeIsAlive(sessionId: Long): Boolean

    /** 阻塞等待会话结束，返回退出码。 */
    external fun nativeWait(sessionId: Long): Int

    /** 关闭会话并回收子进程，杜绝 fd 泄漏与僵尸进程。 */
    external fun nativeClose(sessionId: Long): Boolean

    /** 带降级的可用探测入口。 */
    fun safeAvailable(): Boolean =
        if (nativeOk) {
            try { nativeAvailable() } catch (t: Throwable) { jvmPtmxAvailable() }
        } else {
            jvmPtmxAvailable()
        }

    /** 带降级的打开入口；native 不可用返回 -1。 */
    fun safeOpen(cols: Int, rows: Int, argv: Array<String>): Long =
        if (nativeOk) {
            try { nativeOpen(cols, rows, argv) } catch (t: Throwable) { -1L }
        } else {
            -1L
        }

    /** native 库是否成功加载（供上层决定是否走 native 路径）。 */
    fun isNativeLoaded(): Boolean = nativeOk

    private fun jvmPtmxAvailable(): Boolean = try {
        java.io.File("/dev/ptmx").exists()
    } catch (t: Throwable) {
        false
    }
}

package com.suicoco.nativelib.pty

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * PTY 会话编排内核 —— 把 [PtyBridge] 的 8 个 forkpty JNI 方法封装为
 * 生命周期安全、fail-closed 的高层会话 API。
 *
 * 背景：v0.3.6 已落地真实 arm64 forkpty 会话链，但 PtyBridge 仍是「裸桥」——
 * native 库加载失败（纯 JVM / 未打包 .so 的测试环境）时 safeOpen 直接返回 -1，
 * 上层无法真正执行命令；且没有会话句柄管理，直接暴露 sid 给调用方易造成
 * fd 泄漏 / 重复 close / 跨会话串扰。
 *
 * 本内核补齐三点：
 *  1. **统一句柄**：调用方拿到的是本内核分配的单调递增 handle，内部映射到
 *     native sid 或降级进程，调用方永远接触不到裸 sid。
 *  2. **native 优先 + 进程降级**：native 可用走 forkpty；否则降级到
 *     [ProcessBuilder]（不经 shell、不拼接字符串，杜绝命令注入），使上层在
 *     任何环境都能跑调用方传入的 argv，而不是得到 -1。
 *  3. **fail-closed + 幂等**：空 argv / 空元素 / 含 NUL 一律拒绝；任何 JNI
 *     异常都吞掉并降级为安全返回值；close 幂等且保证回收子进程。
 *
 * 硬性不变量：本内核只执行调用方传入的 argv，不自行提权、不注入命令；
 * no-root 约束由上层 ProotManager.assertNoHostRoot 在 open 之前保证。
 *
 * 纯 Kotlin/JDK 标准库实现，无 Android / Compose / 三方依赖，可独立编译单测。
 */
class PtySessionController(
    private val bridge: PtyBridgeFacade = DefaultPtyBridgeFacade
) {
    /** JNI 适配接口，便于单测注入假实现，隔离 UnsatisfiedLinkError。 */
    interface PtyBridgeFacade {
        fun nativeLoaded(): Boolean
        fun available(): Boolean
        fun open(cols: Int, rows: Int, argv: Array<String>): Long
        fun write(sid: Long, data: ByteArray): Int
        fun read(sid: Long): ByteArray
        fun resize(sid: Long, cols: Int, rows: Int): Boolean
        fun isAlive(sid: Long): Boolean
        fun waitFor(sid: Long): Int
        fun close(sid: Long): Boolean
    }

    /** 默认适配：委托 PtyBridge，并对每个 JNI 调用做异常兜底（fail-closed）。 */
    object DefaultPtyBridgeFacade : PtyBridgeFacade {
        override fun nativeLoaded(): Boolean = try {
            PtyBridge.isNativeLoaded()
        } catch (_: Throwable) {
            false
        }

        override fun available(): Boolean = try {
            PtyBridge.safeAvailable()
        } catch (_: Throwable) {
            false
        }

        override fun open(cols: Int, rows: Int, argv: Array<String>): Long = try {
            PtyBridge.safeOpen(cols, rows, argv)
        } catch (_: Throwable) {
            -1L
        }

        override fun write(sid: Long, data: ByteArray): Int = try {
            PtyBridge.nativeWrite(sid, data)
        } catch (_: Throwable) {
            -1
        }

        override fun read(sid: Long): ByteArray = try {
            PtyBridge.nativeRead(sid)
        } catch (_: Throwable) {
            ByteArray(0)
        }

        override fun resize(sid: Long, cols: Int, rows: Int): Boolean = try {
            PtyBridge.nativeResize(sid, cols, rows)
        } catch (_: Throwable) {
            false
        }

        override fun isAlive(sid: Long): Boolean = try {
            PtyBridge.nativeIsAlive(sid)
        } catch (_: Throwable) {
            false
        }

        override fun waitFor(sid: Long): Int = try {
            PtyBridge.nativeWait(sid)
        } catch (_: Throwable) {
            -1
        }

        override fun close(sid: Long): Boolean = try {
            PtyBridge.nativeClose(sid)
        } catch (_: Throwable) {
            false
        }
    }

    private sealed interface Session {
        data class Native(val sid: Long) : Session
        data class Fallback(val proc: FallbackProcess) : Session
    }

    /**
     * 降级进程：持有 [Process]，pump 线程持续把 stdout/stderr 分块入队，
     * read() 非阻塞 poll；write 直写 stdin；waitFor/destroy 委托进程本身。
     */
    private class FallbackProcess(val process: Process) {
        val chunks = ConcurrentLinkedQueue<ByteArray>()
        val pump: Thread = Thread({
            try {
                val input = process.inputStream
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) chunks.add(buf.copyOf(n))
                }
            } catch (_: Throwable) {
                // 进程被关闭 / 流中断，属正常收尾
            }
        }, "suicoco-pty-fallback-pump").apply { isDaemon = true }

        init {
            pump.start()
        }

        fun write(data: ByteArray): Int = try {
            process.outputStream.write(data)
            process.outputStream.flush()
            data.size
        } catch (_: Throwable) {
            -1
        }

        fun waitFor(): Int = try {
            process.waitFor()
        } catch (_: Throwable) {
            -1
        }

        fun destroy() {
            try { process.destroy() } catch (_: Throwable) { }
            pump.interrupt()
        }
    }

    data class OpenResult(val handle: Long, val native: Boolean)
    data class SessionInfo(val handle: Long, val native: Boolean, val alive: Boolean)

    private val sessions = ConcurrentHashMap<Long, Session>()
    private val nextHandle = AtomicLong(1L)

    /** 底层 PTY 设备 / native 库是否可用（异常一律视为不可用）。 */
    fun available(): Boolean = try {
        bridge.available()
    } catch (_: Throwable) {
        false
    }

    /**
     * 打开会话。argv 必须非空、无空元素、不含 NUL；否则返回 null（fail-closed）。
     * native 可用优先走 forkpty；否则降级到直接启动进程的 FallbackProcess。
     */
    fun open(cols: Int, rows: Int, argv: List<String>): OpenResult? {
        if (argv.isEmpty()) return null
        if (argv.any { it.isEmpty() || it.indexOf('\u0000') >= 0 }) return null
        val c = if (cols > 0) cols else 80
        val r = if (rows > 0) rows else 24

        if (bridge.nativeLoaded()) {
            // JNI 调用可能抛 UnsatisfiedLinkError / 其它运行期异常，
            // 内核层自我防护：吞掉异常并降级到进程，而非让上层崩溃。
            val sid = try {
                bridge.open(c, r, argv.toTypedArray())
            } catch (_: Throwable) {
                -1L
            }
            if (sid > 0) {
                val h = nextHandle.getAndIncrement()
                sessions[h] = Session.Native(sid)
                return OpenResult(h, true)
            }
        }

        // 降级：直接按 argv 启动进程，不经 shell、不拼接字符串（杜绝注入）。
        val proc = try {
            ProcessBuilder(argv).redirectErrorStream(true).start()
        } catch (_: Throwable) {
            return null
        }
        val h = nextHandle.getAndIncrement()
        sessions[h] = Session.Fallback(FallbackProcess(proc))
        return OpenResult(h, false)
    }

    /** 非阻塞读一次输出；无数据返回空数组。 */
    fun read(handle: Long): ByteArray = when (val s = sessions[handle]) {
        is Session.Native -> bridge.read(s.sid)
        is Session.Fallback -> s.proc.chunks.poll() ?: ByteArray(0)
        null -> ByteArray(0)
    }

    /** 写输入，返回写入字节数；异常/无效句柄返回 -1。 */
    fun write(handle: Long, data: ByteArray): Int {
        if (data.isEmpty()) return 0
        return when (val s = sessions[handle]) {
            is Session.Native -> bridge.write(s.sid, data)
            is Session.Fallback -> s.proc.write(data)
            null -> -1
        }
    }

    /** 调整窗口大小；降级进程无 TTY，返回 false。 */
    fun resize(handle: Long, cols: Int, rows: Int): Boolean {
        if (cols <= 0 || rows <= 0) return false
        return when (val s = sessions[handle]) {
            is Session.Native -> bridge.resize(s.sid, cols, rows)
            is Session.Fallback -> false
            null -> false
        }
    }

    fun isAlive(handle: Long): Boolean = when (val s = sessions[handle]) {
        is Session.Native -> bridge.isAlive(s.sid)
        is Session.Fallback -> s.proc.pump.isAlive || s.proc.chunks.isNotEmpty()
        null -> false
    }

    /** 阻塞等待结束并返回退出码；无效句柄返回 -1。 */
    fun waitFor(handle: Long): Int = when (val s = sessions[handle]) {
        is Session.Native -> bridge.waitFor(s.sid)
        is Session.Fallback -> s.proc.waitFor()
        null -> -1
    }

    /** 关闭会话并回收资源；幂等（重复关闭返回 true，不抛异常）。 */
    fun close(handle: Long): Boolean {
        val s = sessions.remove(handle) ?: return true
        return when (s) {
            is Session.Native -> bridge.close(s.sid)
            is Session.Fallback -> {
                s.proc.destroy()
                true
            }
        }
    }

    fun closeAll() {
        sessions.keys.toList().forEach { close(it) }
    }

    fun activeCount(): Int = sessions.size

    fun listSessions(): List<SessionInfo> = sessions.keys.toList().sorted().map {
        val s = sessions[it]
        SessionInfo(it, s is Session.Native, isAlive(it))
    }
}

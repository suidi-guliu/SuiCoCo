package com.suicoco.terminal

import com.suicoco.core.term.CommandExecutor
import com.suicoco.core.term.CommandResult
import com.suicoco.nativelib.pty.PtySessionController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * PTY 会话执行适配器（app 层）——把 [PtySessionController] 会话内核适配为
 * core:term 的 [CommandExecutor] 契约，供安装向导与终端共享同一套执行实现。
 *
 * 背景：v0.3.10 前安装向导走 core:term 的 ProcessRunner（ProcessBuilder），
 * 终端走 PtySessionController，二者是两套互不复用的执行实现。本适配器让向导
 * 也走会话内核（native forkpty 优先 + 进程降级 + fail-closed + 幂等 close），
 * 从而"一套会话、两处复用"，且 core/feature 无需依赖 native 叶子模块。
 *
 * 分层：本类位于 app 层（app 已依赖 :native:pty 与 :core:term），零新增依赖；
 * 与 [TerminalCommandExecutor] 可注入同一个 [PtySessionController] 实例共享会话池。
 *
 * env 说明：[PtySessionController] 的 native execvp 与降级 ProcessBuilder 都会
 * 继承当前进程环境，而 SUICOCO_PROOT 正是从 System.getenv 读出的值，故无需
 * 在会话内核里重复注入（也避免引入 shell 字符串拼接）。env 参数保留以兼容
 * [CommandExecutor] 契约，本后端忽略并在逐行日志中标注会话模式。
 *
 * 纯 Kotlin/JDK 标准库 + PtySessionController + coroutines，无 Android/Compose 引用。
 */
class PtyCommandExecutor(
    private val sessions: PtySessionController = PtySessionController()
) : CommandExecutor {
    /** tag → 会话句柄；供向导"取消"时 close 对应会话。 */
    private val handles = ConcurrentHashMap<String, Long>()

    /**
     * 执行 argv：native 可用走 forkpty，否则由内核降级为直接进程。
     * 逐行读取会话输出并回调 [onLine]，返回退出码。
     */
    override suspend fun run(
        argv: List<String>,
        tag: String,
        env: Map<String, String>,
        onLine: (String) -> Unit
    ): CommandResult = withContext(Dispatchers.IO) {
        require(argv.isNotEmpty()) { "空命令，拒绝执行" }
        val opened = sessions.open(COLS, ROWS, argv)
            ?: return@withContext CommandResult(-1)
        handles[tag] = opened.handle
        onLine("[suicoco] 会话模式: ${if (opened.native) "native PTY" else "进程降级"}")
        try {
            // 字节缓冲：按 0x0A 切行，避免按块 String(bytes) 截断跨块的多字节 UTF-8 字符。
            val pending = ByteArrayOutputStream()
            var drainedTotal = 0
            val deadline = System.currentTimeMillis() + MAX_WAIT_MS

            fun drain() {
                while (true) {
                    val bytes = sessions.read(opened.handle)
                    if (bytes.isEmpty()) break
                    drainedTotal += bytes.size
                    // 防御病态 native read（持续吐数据）：单次会话读取总量封顶，杜绝无限累积 OOM。
                    if (drainedTotal > MAX_DRAIN_BYTES) break
                    pending.write(bytes)
                }
                val all = pending.toByteArray()
                var lineStart = 0
                var cut = 0
                for (i in all.indices) {
                    if (all[i] == 0x0A.toByte()) {
                        var line = String(all, lineStart, i - lineStart, Charsets.UTF_8)
                        if (line.endsWith('\r')) line = line.dropLast(1)
                        onLine(line)
                        lineStart = i + 1
                        cut = lineStart
                    }
                }
                if (cut > 0) {
                    val rest = all.copyOfRange(cut, all.size)
                    pending.reset()
                    pending.write(rest)
                }
            }

            // 向导安装（apt install）可能耗时较久，上限放宽到 30 分钟；
            // 用户主动取消走 cancel(tag) → close 会话 → isAlive 变 false → 循环退出。
            while (System.currentTimeMillis() < deadline) {
                drain()
                if (!sessions.isAlive(opened.handle)) break
                try { Thread.sleep(20L) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            drain()
            if (pending.size() > 0) {
                val tail = String(pending.toByteArray(), Charsets.UTF_8).trimEnd('\r', '\n')
                if (tail.isNotEmpty()) onLine(tail)
            }
            val code = sessions.waitFor(opened.handle)
            CommandResult(code)
        } finally {
            handles.remove(tag)
            sessions.close(opened.handle)
        }
    }

    /** 取消指定会话：关闭句柄（幂等），触发 run 的读取循环退出。 */
    override fun cancel(tag: String) {
        handles.remove(tag)?.let { sessions.close(it) }
    }

    /** 取消全部向导会话。 */
    override fun cancelAll() {
        handles.values.toList().forEach { sessions.close(it) }
        handles.clear()
    }

    fun sessionAvailable(): Boolean = sessions.available()
    fun activeCount(): Int = sessions.activeCount()

    companion object {
        private const val COLS = 120
        private const val ROWS = 40
        /** 向导长任务保护性上限（30 分钟）；非业务超时，用户可随时 cancel。 */
        private const val MAX_WAIT_MS = 30 * 60 * 1000L
        /** 单次会话累计读取上限（32 MiB），防御异常 read 造成的内存耗尽。 */
        private const val MAX_DRAIN_BYTES = 32 * 1024 * 1024
    }
}

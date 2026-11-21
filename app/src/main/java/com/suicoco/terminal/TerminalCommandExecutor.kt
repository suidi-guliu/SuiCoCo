package com.suicoco.terminal

import com.suicoco.nativelib.pty.PtySessionController

/**
 * 终端命令执行器 —— 把 [PtySessionController] 会话编排内核接到终端命令执行层（阶段5）。
 *
 * 背景：v0.3.7 已落地会话编排内核（native forkpty 优先 + 进程降级 + fail-closed），
 * 但 MainViewModel.execTerminalCommand 仍是模拟响应，PTY 能力闲置。本类作为 app 层
 * 适配器（app 已依赖 :native:pty），把「一条命令字符串」安全地转换为 argv 并交给
 * 会话内核真实执行，逐行回传输出，供终端 UI 与 ViewModel 消费。
 *
 * 分层约束：native 为叶子模块（产 .so，core/feature 不依赖它）；本适配器位于 app 层，
 * 不新增任何模块依赖，core:term 的 ProcessRunner 保持面向安装向导的 ProcessBuilder 路径。
 *
 * 安全不变量：
 * - 只执行用户输入的 argv，不经 shell、不拼接命令字符串（杜绝注入）；
 * - fail-closed：空命令 / 空 argv / 含 NUL / 违反 no-root 标记一律拒绝；
 * - no-root 由 ProotManager.assertNoHostRoot 语义在本类做等价前置校验（终端直连场景）。
 *
 * 纯 Kotlin/JDK 标准库 + PtySessionController 依赖，无 Android/Compose/三方引用，可独立编译单测。
 */
class TerminalCommandExecutor(
    private val sessions: PtySessionController = PtySessionController()
) {
    data class ExecResult(
        val exitCode: Int,
        val lines: List<String>,
        val native: Boolean,
        val rejected: Boolean = false
    ) {
        val success: Boolean get() = !rejected && exitCode == 0
    }

    /** no-root 标记：与 ProotManager.assertNoHostRoot 同语义（裸 su/sudo + 危险标记）。 */
    private val noRootMarkers = listOf("shizuku", "--host-root", "libsu.so", "libsu-", "--su=", "--sudo=")

    /**
     * 轻量 shell-like 分词：支持单/双引号与反斜杠转义，不展开变量、不做 glob、不解释元字符。
     * 返回 null 表示词法错误（引号未闭合 / 含 NUL / 空输入），调用方应 fail-closed。
     */
    fun tokenize(input: String): List<String>? {
        if (input.isEmpty()) return null
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        var escaped = false
        var started = false
        for (ch in input) {
            if (ch == '\u0000') return null
            when {
                escaped -> { cur.append(ch); escaped = false; started = true }
                ch == '\\' -> { escaped = true; started = true }
                quote != null -> {
                    if (ch == quote) quote = null else cur.append(ch)
                    started = true
                }
                ch == '\'' || ch == '"' -> { quote = ch; started = true }
                ch.isWhitespace() -> {
                    if (started) { out.add(cur.toString()); cur.setLength(0); started = false }
                }
                else -> { cur.append(ch); started = true }
            }
        }
        if (escaped || quote != null) return null
        if (started) out.add(cur.toString())
        if (out.isEmpty()) return null
        return out
    }

    /** no-root 前置校验：拒绝裸 su/sudo 与危险标记（跳过 argv[0] 之外的实际参数）。 */
    fun isAllowed(argv: List<String>): Boolean {
        if (argv.isEmpty()) return false
        if (argv.any { it.isEmpty() || it.indexOf('\u0000') >= 0 }) return false
        val bad = argv.drop(1).any { a ->
            val t = a.trim().lowercase()
            t == "su" || t == "sudo" || t.startsWith("sudo ") || noRootMarkers.any { t.contains(it) }
        }
        return !bad
    }

    /**
     * 阻塞执行一条命令：native 可用走 forkpty，否则由内核降级为直接进程。
     * 逐行读取会话输出并回调 [onLine]，返回退出码与完整行集合。
     * [shouldContinue] 供调用方在协程中取消时提前终止（避免长命令无限占用 IO 线程）。
     */
    fun execute(
        argv: List<String>,
        cols: Int = 80,
        rows: Int = 24,
        maxWaitMs: Long = 30_000L,
        onLine: (String) -> Unit = {},
        shouldContinue: () -> Boolean = { true }
    ): ExecResult {
        if (!isAllowed(argv)) return ExecResult(-1, emptyList(), false, rejected = true)
        val opened = sessions.open(cols, rows, argv)
            ?: return ExecResult(-1, emptyList(), false)
        val collected = mutableListOf<String>()
        try {
            // 字节缓冲：按 0x0A 切行，避免按块 String(bytes) 截断跨块的多字节 UTF-8 字符。
            val pending = java.io.ByteArrayOutputStream()
            var drainedTotal = 0
            val deadline = System.currentTimeMillis() + maxWaitMs

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
                        collected.add(line)
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

            while (shouldContinue() && System.currentTimeMillis() < deadline) {
                drain()
                if (!sessions.isAlive(opened.handle)) break
                try { Thread.sleep(20L) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
            }
            drain()
            if (pending.size() > 0) {
                val tail = String(pending.toByteArray(), Charsets.UTF_8).trimEnd('\r', '\n')
                if (tail.isNotEmpty()) { onLine(tail); collected.add(tail) }
            }
            val code = sessions.waitFor(opened.handle)
            return ExecResult(code, collected, opened.native)
        } finally {
            sessions.close(opened.handle)
        }
    }

    companion object {
        /** 单次会话累计读取上限（16 MiB），防御异常 read 造成的内存耗尽。 */
        private const val MAX_DRAIN_BYTES = 16 * 1024 * 1024
    }

    fun sessionAvailable(): Boolean = sessions.available()
    fun activeCount(): Int = sessions.activeCount()
    fun closeAll() = sessions.closeAll()
}

package com.suicoco.core.term

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap

/**
 * 本地进程执行器 —— 真实启动 proot 命令并逐行回传 stdout/stderr。
 *
 * 设计要点：
 * - 使用 JDK ProcessBuilder 启动 `ProotManager.buildCommand` 产生的 argv，
 *   不依赖 native/pty 的 JNI 骨架（真实 libproot.so 实体化属阶段5，见 INSTALLER_DESIGN §6），
 *   因此在本模块即可完成"逐行执行 + 日志回传"的闭环并可被 CI 编译验证。
 * - redirectErrorStream(true)：proot 的 apt 进度/告警走 stderr，合并后按行回调，
 *   保证向导日志不丢行、时序正确。
 * - 每个会话以 tag 登记 Process，支持向导"取消"时 destroy()，避免遗留子进程。
 * - 硬性不变量：本类只执行调用方传入的 argv，绝不自行提权；
 *   no-root 约束由 [ProotManager.assertNoHostRoot] 在调用前校验。
 */
class ProcessRunner : CommandExecutor {
    private val active = ConcurrentHashMap<String, Process>()

    /**
     * 执行 argv，逐行回调日志，返回退出结果。
     *
     * @param tag 会话标识（取消时按此 destroy）
     * @param env 注入的环境变量（如 SUICOCO_PROOT）
     * @param onLine 每读到一行输出回调一次（已去除换行符）
     */
    override suspend fun run(
        argv: List<String>,
        tag: String,
        env: Map<String, String>,
        onLine: (String) -> Unit
    ): CommandResult = withContext(Dispatchers.IO) {
        require(argv.isNotEmpty()) { "空命令，拒绝执行" }
        val builder = ProcessBuilder(argv).redirectErrorStream(true)
        env.forEach { (k, v) -> builder.environment()[k] = v }
        val process = builder.start()
        active[tag] = process
        try {
            BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    onLine(line ?: "")
                }
            }
            val code = process.waitFor()
            CommandResult(exitCode = code)
        } finally {
            active.remove(tag)
        }
    }

    /** 取消指定会话（ destroy 子进程；已结束则无副作用）。 */
    override fun cancel(tag: String) {
        active[tag]?.destroy()
        active.remove(tag)
    }

    /** 取消全部在跑的会话。 */
    override fun cancelAll() {
        active.values.forEach { it.destroy() }
        active.clear()
    }
}

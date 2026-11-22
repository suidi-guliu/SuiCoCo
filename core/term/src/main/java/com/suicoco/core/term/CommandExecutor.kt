package com.suicoco.core.term

/**
 * 命令执行结果 —— 与后端无关的轻量值对象。
 *
 * 无论是 [ProcessRunner] 的纯 JVM ProcessBuilder 路径，还是 app 层注入的
 * PTY 会话执行器（native forkpty 优先 + 进程降级），都统一返回本类型。
 */
data class CommandResult(val exitCode: Int) {
    val success: Boolean get() = exitCode == 0
}

/**
 * 命令执行契约 —— core:term 定义的纯 Kotlin 抽象。
 *
 * 设计目的：消除"两套执行实现"。此前安装向导 [ProcessRunner] 走 ProcessBuilder，
 * 终端走 PtySessionController，二者互不复用。现在 core:term 只持有本接口，
 * 具体后端由上层注入：
 * - 默认 [ProcessRunner]：保持 core/feature 层可独立编译与单测（零 native 依赖）；
 * - app 层 PtyCommandExecutor：把 PtySessionController 会话内核适配为本接口，
 *   使向导与终端共享同一套 fail-closed 会话（native 优先 + 进程降级）。
 *
 * 分层约束不变：core 不依赖 feature，core/feature 均不依赖 native 叶子模块。
 * 硬性不变量：实现只执行调用方传入的 argv，不自行提权、不注入命令；
 * no-root 由上层 ProotManager.assertNoHostRoot 在 run 之前保证。
 */
interface CommandExecutor {
    /**
     * 执行 argv，逐行回调输出，返回退出结果。
     *
     * @param tag 会话标识（取消时按此定位）
     * @param env 需追加到子进程的环境变量（后端若已继承进程环境可忽略）
     */
    suspend fun run(
        argv: List<String>,
        tag: String = "default",
        env: Map<String, String> = emptyMap(),
        onLine: (String) -> Unit = {}
    ): CommandResult

    /** 取消指定会话（幂等；已结束无副作用）。 */
    fun cancel(tag: String)

    /** 取消全部在跑的会话。 */
    fun cancelAll()
}

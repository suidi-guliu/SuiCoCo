package com.suicoco.feature.onboarding

import android.content.Context
import com.suicoco.core.distro.DistroProfile
import com.suicoco.core.distro.ProotManager
import com.suicoco.core.distro.RootfsManager
import com.suicoco.core.term.CommandExecutor
import com.suicoco.core.term.ProcessRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** 安装向导步骤。 */
enum class InstallStep(val label: String) {
    IDLE("待开始"),
    DOWNLOADING("下载 Ubuntu 24.04 rootfs"),
    EXTRACTING("解压 rootfs"),
    UPDATING("更新 apt 软件源"),
    INSTALLING("安装语言工具链"),
    DONE("安装完成"),
    FAILED("安装失败"),
    CANCELLED("已取消")
}

/** 向导 UI 状态（单一不可变快照，便于 Compose 观察与测试）。 */
data class InstallerState(
    val step: InstallStep = InstallStep.IDLE,
    val progress: Float = 0f,          // 0..1，下载阶段有效
    val log: List<String> = emptyList(),
    val error: String? = null,
    val installedToolchains: List<String> = emptyList()
) {
    val running: Boolean
        get() = step in setOf(
            InstallStep.DOWNLOADING, InstallStep.EXTRACTING,
            InstallStep.UPDATING, InstallStep.INSTALLING
        )
    val finished: Boolean get() = step in setOf(InstallStep.DONE, InstallStep.FAILED, InstallStep.CANCELLED)
}

/**
 * 安装流程控制器 —— 编排 RootfsManager / ProotManager / ProcessRunner，
 * 全程逐行回传日志，硬性不变量：无 host root、官方源、仅 arm64-v8a。
 *
 * 与 Compose 解耦：UI 仅观察 [state] 并调用 [start] / [cancel]，便于单元测试。
 *
 * v0.3.11：执行后端从写死的 [ProcessRunner] 改为可注入的 [CommandExecutor] 契约。
 * 默认仍用 ProcessRunner（零 native 依赖，feature 层可独立单测）；app 层可注入
 * PtyCommandExecutor，使向导与终端共享同一套 fail-closed 会话（native 优先 + 降级）。
 */
class InstallerController(
    context: Context,
    private val runner: CommandExecutor = ProcessRunner()
) {
    private val appContext = context.applicationContext
    private val rootfs = RootfsManager(appContext)
    private val proot = ProotManager(appContext)
    private val gate = Mutex()

    private val _state = MutableStateFlow(InstallerState())
    val state: StateFlow<InstallerState> = _state.asStateFlow()

    /** 启动安装（幂等锁：运行中重复调用直接忽略）。 */
    suspend fun start(profile: DistroProfile = DistroProfile.UBUNTU_2404) {
        if (!gate.tryLock()) return
        try {
            _state.value = InstallerState(step = InstallStep.DOWNLOADING)
            appendLog("开始安装 ${profile.name} ${profile.version} (${profile.arch})")
            appendLog("下载源: ${profile.downloadUrl}")

            // 1) 下载 rootfs（官方源 + SHA-256 校验在 RootfsManager 内完成）
            val archive = rootfs.download(profile) { downloaded, total ->
                val p = if (total > 0) downloaded.toFloat() / total else 0f
                _state.value = _state.value.copy(progress = p.coerceIn(0f, 1f))
            }
            appendLog("rootfs 下载完成 (${archive.length() / 1024 / 1024} MiB)，开始解压")

            // 2) 解压到 app 私有目录（纯 Kotlin tar 解析，含路径穿越防护）
            transition(InstallStep.EXTRACTING)
            val rootDir: File = rootfs.extract(archive)
            appendLog("rootfs 解压完成: ${rootDir.absolutePath}")

            // 3) 通过 proot fake-root 执行 apt 更新与工具链安装（无 su/sudo）
            transition(InstallStep.UPDATING)
            val env = buildEnv()
            val updateArgv = proot.buildCommand(
                rootfs = rootDir.absolutePath,
                command = listOf("bash", "-lc", "apt-get update")
            )
            proot.assertNoHostRoot(updateArgv)
            appendLog("执行: apt-get update（proot fake-root，无 host root）")
            val updateResult = runner.run(updateArgv, tag = TAG_UPDATE, env = env, onLine = ::appendLog)
            if (!updateResult.success) {
                fail("apt-get update 失败，退出码 ${updateResult.exitCode}")
                return
            }

            transition(InstallStep.INSTALLING)
            val installed = mutableListOf<String>()
            for (tool in proot.toolchains) {
                appendLog("── 安装 ${tool.name} (${tool.id}) ──")
                val argv = proot.buildCommand(
                    rootfs = rootDir.absolutePath,
                    command = proot.aptInstallCommand(tool.packages)
                )
                proot.assertNoHostRoot(argv)
                val result = runner.run(argv, tag = TAG_INSTALL, env = env, onLine = ::appendLog)
                if (!result.success) {
                    fail("${tool.name} 安装失败，退出码 ${result.exitCode}")
                    return
                }
                installed += tool.id
                _state.value = _state.value.copy(installedToolchains = installed.toList())
            }

            _state.value = _state.value.copy(
                step = InstallStep.DONE, progress = 1f, installedToolchains = installed.toList()
            )
            appendLog("✓ 安装完成，已就绪工具链: ${installed.joinToString(", ")}")
        } catch (e: CancellationException) {
            _state.value = _state.value.copy(step = InstallStep.CANCELLED)
            appendLog("安装已取消")
        } catch (e: Exception) {
            fail(e.message ?: e::class.java.simpleName)
        } finally {
            gate.unlock()
        }
    }

    /** 取消正在执行的子进程与流程。 */
    fun cancel() {
        runner.cancel(TAG_UPDATE)
        runner.cancel(TAG_INSTALL)
    }

    /**
     * 构造注入环境：若构建/运行时通过 SUICOCO_PROOT 指定了解压出的 libproot.so，
     * 则透传给子进程供 proot 构建器解析；不影响默认 JNI 库查找路径。
     */
    private fun buildEnv(): Map<String, String> {
        val injected = System.getenv("SUICOCO_PROOT")
        return if (injected.isNullOrBlank()) emptyMap() else mapOf("SUICOCO_PROOT" to injected)
    }

    private fun transition(step: InstallStep) {
        _state.value = _state.value.copy(step = step)
    }

    private fun fail(message: String) {
        _state.value = _state.value.copy(step = InstallStep.FAILED, error = message)
        appendLog("✗ 失败: $message")
    }

    /** 逐行追加日志，保留最近 MAX_LOG 行，避免长安装 OOM。 */
    private fun appendLog(line: String) {
        val current = _state.value.log
        _state.value = _state.value.copy(
            log = if (current.size >= MAX_LOG) current.drop(current.size - MAX_LOG + 1) + line else current + line
        )
    }

    companion object {
        private const val TAG_UPDATE = "onboarding-update"
        private const val TAG_INSTALL = "onboarding-install"
        private const val MAX_LOG = 500
    }
}

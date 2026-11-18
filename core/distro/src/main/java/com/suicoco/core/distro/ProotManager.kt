package com.suicoco.core.distro

import android.content.Context
import java.io.File

/** proot 命令构建 — 硬性不变量：绝不请求 host root */
class ProotManager(private val context: Context) {
    val toolchains = listOf(
        Toolchain("cpp", "C/C++ 工具链", listOf("gcc", "g++", "gdb", "cmake", "make"), "gcc --version", Toolchain.Category.LANGUAGE),
        Toolchain("python", "Python 环境", listOf("python3", "python3-pip", "python3-dev"), "python3 --version", Toolchain.Category.LANGUAGE),
        Toolchain("java", "Java 环境", listOf("openjdk-17-jdk", "maven"), "java -version", Toolchain.Category.LANGUAGE),
        Toolchain("git", "Git", listOf("git"), "git --version", Toolchain.Category.SYSTEM)
    )

    fun buildCommand(rootfs: String, command: List<String>, binds: List<Pair<String, String>> = emptyList(), workdir: String = "/workspace"): List<String> {
        val prootBinary = resolveProotBinary()
        val argv = mutableListOf<String>(prootBinary)
        argv += listOf("-r", rootfs, "-0")           // fake root，绝不 su
        argv += listOf("-b", "/dev:/dev", "-b", "/proc:/proc", "-b", "/sys:/sys")
        binds.forEach { (h, t) -> argv += listOf("-b", "$h:$t") }
        argv += listOf("-w", workdir, "--kill-on-exit", "--link2symlink", "--sysvipc", "-k", "6.1.0", "--verbose=-1")
        argv += command
        return argv
    }

    /**
     * 解析 libproot.so 路径：
     * 1) 优先使用 SUICOCO_PROOT 环境变量注入的绝对路径（供外部/测试注入真实 arm64 .so，阶段5 实体化路径）；
     * 2) 否则回退到 app nativeLibraryDir 中随包集成的 libproot.so。
     */
    private fun resolveProotBinary(): String {
        val injected = System.getenv(ENV_PROOT)?.trim()
        if (!injected.isNullOrEmpty()) {
            val f = File(injected)
            if (f.isFile && f.canExecute()) return f.absolutePath
        }
        return File(context.applicationInfo.nativeLibraryDir, "libproot.so").absolutePath
    }

    fun aptInstallCommand(packages: List<String>): List<String> =
        listOf("bash", "-lc", "DEBIAN_FRONTEND=noninteractive apt-get install -y " + packages.joinToString(" "))

    companion object {
        const val ENV_PROOT = "SUICOCO_PROOT"
    }

    fun assertNoHostRoot(argv: List<String>) {
        // argv[0] 是 proot 可执行文件的绝对路径，其中含 "suicoco" 子串，
        // 旧版对整条 argv 做 "su" 子串匹配会造成自我误判；改为：
        // - 跳过 argv[0]，仅检查实际命令参数；
        // - 裸 su/sudo 用整词匹配（含 "sudo " 前缀形式）；
        // - 明确的危险标记（shizuku / --host-root / libsu.so 等）仍用子串匹配。
        val markers = listOf("shizuku", "--host-root", "libsu.so", "libsu-")
        val bad = argv.drop(1).firstOrNull { a ->
            val t = a.trim().lowercase()
            t == "su" || t == "sudo" || t.startsWith("sudo ") || markers.any { t.contains(it) }
        }
        require(bad == null) { "违反无-root 约束: $bad" }
    }
}

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
        val prootBinary = File(context.applicationInfo.nativeLibraryDir, "libproot.so").absolutePath
        val argv = mutableListOf<String>(prootBinary)
        argv += listOf("-r", rootfs, "-0")           // fake root，绝不 su
        argv += listOf("-b", "/dev:/dev", "-b", "/proc:/proc", "-b", "/sys:/sys")
        binds.forEach { (h, t) -> argv += listOf("-b", "$h:$t") }
        argv += listOf("-w", workdir, "--kill-on-exit", "--link2symlink", "--sysvipc", "-k", "6.1.0", "--verbose=-1")
        argv += command
        return argv
    }

    fun aptInstallCommand(packages: List<String>): List<String> =
        listOf("bash", "-lc", "apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y " + packages.joinToString(" "))

    fun assertNoHostRoot(argv: List<String>) {
        val forbidden = listOf("su", "sudo", "libsu", "shizuku", "--host-root")
        val bad = argv.firstOrNull { a -> forbidden.any { a.contains(it, ignoreCase = true) } }
        require(bad == null) { "违反无-root 约束: $bad" }
    }
}

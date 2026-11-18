package com.suicoco.core.distro

/** 架构：suicoco 仅支持 arm64-v8a 平板 */
enum class Arch { ARM64, UNKNOWN }

data class DistroProfile(
    val id: String,
    val name: String,
    val version: String,
    val arch: Arch,
    val downloadUrl: String,
    val sha256: String? = null,
    val sizeMb: Int = 0
) {
    companion object {
        /** Ubuntu 24.04 LTS ARM64 官方 rootfs */
        val UBUNTU_2404 = DistroProfile(
            id = "ubuntu-24.04", name = "Ubuntu", version = "24.04 LTS", arch = Arch.ARM64,
            // v0.2.4 修复: 原 partner-images.canonical.com 地址已 404 失效，
            // 改用 cdimage.ubuntu.com 官方 ubuntu-base（与 scripts/install-ubuntu-minimal.sh 一致）
            downloadUrl = "https://cdimage.ubuntu.com/ubuntu-base/releases/noble/release/ubuntu-base-24.04.5-base-arm64.tar.gz",
            sha256 = "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2",
            sizeMb = 33
        )
    }
}

data class Toolchain(
    val id: String,
    val name: String,
    val packages: List<String>,
    val checkCommand: String,
    val category: Category
) {
    enum class Category { SYSTEM, LANGUAGE }
}

data class DistroState(
    val installed: Boolean = false,
    val rootfsPath: String? = null,
    val installedToolchains: Set<String> = emptySet()
)

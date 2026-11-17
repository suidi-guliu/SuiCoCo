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
            downloadUrl = "https://partner-images.canonical.com/apportable/ubuntu/noble/current/ubuntu-noble-arm64.tar.gz",
            sizeMb = 90
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

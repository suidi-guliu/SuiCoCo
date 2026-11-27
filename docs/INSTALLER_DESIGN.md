# 图形化安装向导设计

## 1. 目标

让使用者通过**纯图形化操作**完成两层安装，无需命令行、无需 root：

1. **Ubuntu 精简系统**（L1）：下载官方 ARM64 rootfs，经 proot 在安卓用户空间运行
2. **语言环境**（L4）：apt 官方源安装 C/C++、Python、Java 工具链

## 2. 分层依赖

```
[系统未安装] → 语言环境安装按钮禁用
[系统已安装] → 可安装任意语言环境
```

强约束：未安装 Ubuntu 底座时，不允许安装语言环境。

## 3. 向导步骤

| 步骤 | 标题 | 来源 | 执行 |
|---|---|---|---|
| 1 | 下载 Ubuntu 24.04 LTS (ARM64) | cdimage.ubuntu.com 官方 ubuntu-base（SHA-256 强校验） | RootfsManager.download + extract |
| 2 | C/C++ 工具链 | Ubuntu apt 官方源 | apt-get install gcc g++ gdb cmake make |
| 3 | Python 环境 | Ubuntu apt 官方源 | apt-get install python3 python3-pip python3-dev |
| 4 | Java 环境 | Ubuntu apt 官方源 | apt-get install openjdk-17-jdk maven |

每步显示：标题、来源徽标（🔒 官方源）、状态（待安装/安装中/完成）、进度。

> **步骤 1 来源说明**：rootfs 固定取自 `cdimage.ubuntu.com` 的 `ubuntu-base-24.04.5-base-arm64.tar.gz`（`DistroProfile.UBUNTU_2404.downloadUrl`），与 `scripts/install-ubuntu-minimal.sh` 的下载源一致；官方 `SHA256SUMS` 校验值内置于 `DistroProfile.UBUNTU_2404.sha256`，`RootfsManager.download` 下载完成后强制比对，不匹配即删除归档并抛错（非「尽力而为」校验）。`archive.ubuntu.com` 仅作为步骤 2～4 的 apt 软件包源，不是 rootfs 镜像来源。

## 4. 无 root 实现

- 隔离完全在 proot 用户空间，`-0` 提供 guest 内的 fake root（apt 需要）
- 无 su、无 libsu、无 Shizuku
- rootfs 存放于 app 私有目录 `filesDir/distro/ubuntu-24.04`

## 5. proot 命令（ProotManager.buildCommand）

```
libproot.so -r <rootfs> -0 \
  -b /dev:/dev -b /proc:/proc -b /sys:/sys \
  -w /workspace --kill-on-exit --link2symlink --sysvipc \
  -k 6.1.0 --verbose=-1 <command...>
```

## 6. 原型与真实执行

- 网页/原型阶段：向导步骤为模拟（点击即标记完成）
- 安卓真机阶段（阶段5，2026-11-18 修订）：每步通过 proot PTY 执行真实脚本，逐行回传日志与进度；由 `:core:distro` 的 RootfsManager / ProotManager 驱动，`:native:proot` / `:native:pty` 提供真实 arm64-v8a JNI 桥
- 原定「Electron 桌面壳」方案已随 v0.2.0 方向修正弃用（归档于 `legacy-web-prototype/`），不再作为执行载体
- 脚本已随 v0.2.4 从 legacy-web-prototype 迁移并适配为 **proot 版**：`scripts/install-ubuntu-minimal.sh`（下载 cdimage.ubuntu.com 官方 ubuntu-base rootfs + SHA256 校验 + proot fake-root 刷新 apt 索引）与 `scripts/install-lang-env.sh`（proot 内 apt 官方源装工具链），全程无 su/sudo/chroot；真机由 app 经 PTY 执行并通过 `SUICOCO_PROOT` 注入释放的 `libproot.so`（arm64-v8a），输出逐行回传向导日志
- rootfs 下载由 `:core:distro` `RootfsManager.download` 完成并对内置官方 SHA256SUMS 值做强校验（不匹配即删除并抛错），解压由 `RootfsManager.extract` 完成（纯 Kotlin ustar/PAX/GNU tar 解析 + 路径穿越防护），与脚本下载源（`DistroProfile.UBUNTU_2404`，cdimage.ubuntu.com ubuntu-base 24.04.5 arm64）保持一致

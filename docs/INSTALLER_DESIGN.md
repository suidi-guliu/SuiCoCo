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
| 1 | 下载 Ubuntu 24.04 LTS (ARM64) | archive.ubuntu.com 官方源 | RootfsManager.download + extract |
| 2 | C/C++ 工具链 | Ubuntu apt 官方源 | apt-get install gcc g++ gdb cmake make |
| 3 | Python 环境 | Ubuntu apt 官方源 | apt-get install python3 python3-pip python3-dev |
| 4 | Java 环境 | Ubuntu apt 官方源 | apt-get install openjdk-17-jdk maven |

每步显示：标题、来源徽标（🔒 官方源）、状态（待安装/安装中/完成）、进度。

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
- 桌面/真机阶段：每步通过 proot PTY 执行真实脚本，逐行回传日志与进度
- 脚本见 `scripts/install-ubuntu-minimal.sh` 与 `scripts/install-lang-env.sh`（归档于 legacy-web-prototype，待迁移适配）

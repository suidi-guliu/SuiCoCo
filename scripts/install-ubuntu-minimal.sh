#!/usr/bin/env bash
# ============================================================
# suicoco — Ubuntu 精简系统环境安装脚本（proot 版，无 host root）
# 由软件“图形化安装向导”通过 PTY 调用（输出逐行回传日志），也可手动执行。
#
# 与旧版（debootstrap + sudo chroot，需要 host root，已随 v0.2.4 弃用）不同：
#   1. 直接下载 Ubuntu 官方 base rootfs tarball（cdimage.ubuntu.com 官方源）；
#   2. 解压到目标目录后，用 proot fake-root（-0）在用户空间执行 apt；
#   3. 全程无 su / sudo / libsu / Shizuku，符合项目“无 host root”不变量。
#
# 下载源与 :core:distro DistroProfile.UBUNTU_2404 保持一致。
# 安装位置: $SUICOCO_HOME/envs/ubuntu-base
# 环境变量:
#   SUICOCO_HOME        安装根目录（默认 ~/.suicoco）
#   SUICOCO_TARGET      目标 rootfs 目录（默认 $SUICOCO_HOME/envs/ubuntu-base）
#   SUICOCO_PROOT       proot 可执行文件路径（真机路径为 app 释放的 libproot.so）
#   SUICOCO_UBUNTU_MIRROR  apt 镜像源（默认官方 archive.ubuntu.com）
# ============================================================
set -euo pipefail

SUICOCO_HOME="${SUICOCO_HOME:-$HOME/.suicoco}"
TARGET="${SUICOCO_TARGET:-$SUICOCO_HOME/envs/ubuntu-base}"
ROOTFS_URL="${SUICOCO_ROOTFS_URL:-https://cdimage.ubuntu.com/ubuntu-base/releases/noble/release/ubuntu-base-24.04.5-base-arm64.tar.gz}"
SHA256_URL="${SUICOCO_ROOTFS_SHA256_URL:-https://cdimage.ubuntu.com/ubuntu-base/releases/noble/release/SHA256SUMS}"
SUITE="${SUICOCO_UBUNTU_SUITE:-noble}"
MIRROR="${SUICOCO_UBUNTU_MIRROR:-http://archive.ubuntu.com/ubuntu}"

find_proot() {
  if [ -n "${SUICOCO_PROOT:-}" ] && [ -x "$SUICOCO_PROOT" ]; then
    echo "$SUICOCO_PROOT"; return
  fi
  if command -v proot >/dev/null 2>&1; then
    command -v proot; return
  fi
  echo "错误: 未找到 proot。真机路径由 app 从 native 层释放 libproot.so（arm64-v8a）后通过 SUICOCO_PROOT 注入。" >&2
  exit 1
}
PROOT="$(find_proot)"

ARCH="$(uname -m)"
case "$ARCH" in
  aarch64|arm64) ;;
  *) echo "错误: suicoco 仅支持 arm64-v8a，当前架构: $ARCH" >&2; exit 1 ;;
esac

mkdir -p "$SUICOCO_HOME/envs"

if [ -d "$TARGET/bin/bash" ]; then
  echo "Ubuntu 精简系统环境已存在: $TARGET（如需重装请先删除该目录）"
  exit 0
fi

TARBALL="$SUICOCO_HOME/envs/.rootfs-ubuntu-${SUITE}-arm64.tar.gz"

echo "==> [1/4] 从官方源下载 Ubuntu 24.04 rootfs (arm64) ..."
echo "    $ROOTFS_URL"
mkdir -p "$(dirname "$TARBALL")"
curl -fL --retry 3 --progress-bar -o "$TARBALL" "$ROOTFS_URL"

echo "==> [2/4] 校验 SHA256（官方 SHA256SUMS）..."
if SUMS="$(curl -fsL --retry 2 "$SHA256_URL")"; then
  EXPECTED="$(printf '%s\n' "$SUMS" | grep "ubuntu-base-.*-arm64.tar.gz" | awk '{print $1}' | tail -1)"
  ACTUAL="$(sha256sum "$TARBALL" | awk '{print $1}')"
  if [ -n "$EXPECTED" ] && [ "$EXPECTED" != "$ACTUAL" ]; then
    echo "错误: SHA256 校验失败！expected=$EXPECTED actual=$ACTUAL" >&2
    rm -f "$TARBALL"; exit 1
  fi
  echo "    SHA256 校验通过 ✓"
else
  echo "    警告: 无法获取 SHA256SUMS，跳过校验（网络受限环境）"
fi

echo "==> [3/4] 解压 rootfs 到 $TARGET ..."
mkdir -p "$TARGET"
tar -xzf "$TARBALL" -C "$TARGET"
rm -f "$TARBALL"

# 精简容器内必需的挂载点目录（proot 也会绑定，但预先建好更稳）
mkdir -p "$TARGET/dev" "$TARGET/proc" "$TARGET/sys" "$TARGET/var/lib/dpkg/updates"

echo "==> [4/4] 写入官方 APT 源并在 proot 内刷新索引（fake root，无 host root）..."
cat > "$TARGET/etc/apt/sources.list" <<EOF
deb $MIRROR $SUITE main restricted universe multiverse
deb $MIRROR $SUITE-updates main restricted universe multiverse
deb $MIRROR $SUITE-security main restricted universe multiverse
EOF

"$PROOT" -0 -r "$TARGET" \
  -b /dev:/dev -b /proc:/proc -b /sys:/sys \
  --kill-on-exit --link2symlink \
  /usr/bin/env -i \
    PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    HOME=/root DEBIAN_FRONTEND=noninteractive \
  apt-get update

echo "✓ Ubuntu 精简系统环境安装完成: $TARGET（proot 用户空间，无 host root）"

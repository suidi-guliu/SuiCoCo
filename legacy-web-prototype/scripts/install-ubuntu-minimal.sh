#!/usr/bin/env bash
# ============================================================
# suicoco — Ubuntu 精简系统环境安装脚本（官方来源下载）
# 由软件“图形化安装向导”调用，也可手动执行。
# 原理: debootstrap --variant=minbase 构建最小 Ubuntu 用户态，
#       作为 suicoco 的运行底座与语言环境容器（L1 层）。
# 安装位置: ~/.suococo/envs/ubuntu-base
# ============================================================
set -euo pipefail

SUICOCO_HOME="${SUICOCO_HOME:-$HOME/.suicoco}"
TARGET="$SUICOCO_HOME/envs/ubuntu-base"
SUITE="${SUICOCO_UBUNTU_SUITE:-noble}"                 # Ubuntu 24.04 LTS
MIRROR="${SUICOCO_UBUNTU_MIRROR:-http://archive.ubuntu.com/ubuntu}"

need() { command -v "$1" >/dev/null || { echo "缺少依赖: $1，请先安装"; exit 1; } }
need debootstrap
need sudo

mkdir -p "$SUICOCO_HOME/envs"

if [ -d "$TARGET/etc" ]; then
  echo "Ubuntu 精简系统环境已存在: $TARGET （如需重装请先删除该目录）"
  exit 0
fi

echo "==> 从官方源 $MIRROR 安装 Ubuntu 精简系统 ($SUITE minbase) ..."
sudo debootstrap --variant=minbase --include=apt,ca-certificates "$SUITE" "$TARGET" "$MIRROR"

# 写入官方 APT 源与最小配置
sudo tee "$TARGET/etc/apt/sources.list" >/dev/null <<EOF
deb $MIRROR $SUITE main restricted universe multiverse
deb $MIRROR $SUITE-updates main restricted universe multiverse
deb $MIRROR $SUITE-security main restricted universe multiverse
EOF

# 让 apt 在无 systemd 的精简容器内可用
sudo mkdir -p "$TARGET/var/lib/dpkg/updates"

echo "==> 刷新软件源索引（chroot 内）..."
sudo chroot "$TARGET" apt-get update -qq

echo "✓ Ubuntu 精简系统环境安装完成: $TARGET"

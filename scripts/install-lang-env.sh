#!/usr/bin/env bash
# ============================================================
# suicoco — 语言环境安装脚本（官方来源下载）
# 用法: scripts/install-lang-env.sh <cpp|python|java>
# 依赖: 已通过 install-ubuntu-minimal.sh 安装精简系统底座。
# 在精简系统容器内以 apt 官方源安装对应语言工具链（L4 层）。
# ============================================================
set -euo pipefail

ENV_NAME="${1:?用法: install-lang-env.sh <cpp|python|java>}"
SUICOCO_HOME="${SUICOCO_HOME:-$HOME/.suicoco}"
BASE="$SUICOCO_HOME/envs/ubuntu-base"

[ -d "$BASE/etc" ] || {
  echo "错误: 未找到 Ubuntu 精简系统底座 $BASE"
  echo "请先执行: scripts/install-ubuntu-minimal.sh"
  exit 1
}

case "$ENV_NAME" in
  cpp)    PACKAGES="build-essential gdb" ;;
  python) PACKAGES="python3 python3-pip python3-venv" ;;
  java)   PACKAGES="openjdk-17-jdk" ;;
  *) echo "未知语言环境: $ENV_NAME（支持 cpp|python|java）"; exit 1 ;;
esac

echo "==> 在精简系统容器内安装（官方源）: $PACKAGES"
sudo chroot "$BASE" apt-get install -y --no-install-recommends $PACKAGES

# 写环境标记，供 suicoco 软件本体读取（状态栏/插件运行前检查）
mkdir -p "$SUICOCO_HOME/envs/.installed"
touch "$SUICOCO_HOME/envs/.installed/$ENV_NAME"

echo "✓ 语言环境 $ENV_NAME 安装完成（apt 官方源）"
case "$ENV_NAME" in
  cpp)    sudo chroot "$BASE" gcc --version  | head -1 ;;
  python) sudo chroot "$BASE" python3 --version ;;
  java)   sudo chroot "$BASE" javac -version 2>&1 | head -1 ;;
esac

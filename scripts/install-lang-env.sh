#!/usr/bin/env bash
# ============================================================
# suicoco — 语言环境安装脚本（proot 版，无 host root）
# 用法: scripts/install-lang-env.sh <cpp|python|java>
# 依赖: 已通过 install-ubuntu-minimal.sh 安装精简系统底座。
# 在精简系统容器内经 proot fake-root 用 apt 官方源安装语言工具链（L4 层），
# 全程无 su / sudo / chroot，符合项目“无 host root”不变量。
#
# 环境变量:
#   SUICOCO_HOME    安装根目录（默认 ~/.suicoco）
#   SUICOCO_TARGET  rootfs 目录（默认 $SUICOCO_HOME/envs/ubuntu-base）
#   SUICOCO_PROOT   proot 可执行文件路径（真机路径为 app 释放的 libproot.so）
# ============================================================
set -euo pipefail

ENV_NAME="${1:?用法: install-lang-env.sh <cpp|python|java>}"
SUICOCO_HOME="${SUICOCO_HOME:-$HOME/.suicoco}"
BASE="${SUICOCO_TARGET:-$SUICOCO_HOME/envs/ubuntu-base}"

[ -d "$BASE/bin/bash" ] || {
  echo "错误: 未找到 Ubuntu 精简系统底座 $BASE"
  echo "请先执行: scripts/install-ubuntu-minimal.sh"
  exit 1
}

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

case "$ENV_NAME" in
  cpp)    PACKAGES="build-essential gdb" ;;
  python) PACKAGES="python3 python3-pip python3-venv" ;;
  java)   PACKAGES="openjdk-17-jdk" ;;
  *) echo "未知语言环境: $ENV_NAME（支持 cpp|python|java）"; exit 1 ;;
esac

run_in_proot() {
  "$PROOT" -0 -r "$BASE" \
    -b /dev:/dev -b /proc:/proc -b /sys:/sys \
    --kill-on-exit --link2symlink \
    /usr/bin/env -i \
      PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
      HOME=/root DEBIAN_FRONTEND=noninteractive \
    "$@"
}

echo "==> [1/2] 在 proot 容器内刷新 apt 索引（官方源）..."
run_in_proot apt-get update

echo "==> [2/2] 安装（apt 官方源）: $PACKAGES"
run_in_proot apt-get install -y --no-install-recommends $PACKAGES

# 写环境标记，供 suicoco 软件本体读取（状态栏/插件运行前检查）
mkdir -p "$SUICOCO_HOME/envs/.installed"
touch "$SUICOCO_HOME/envs/.installed/$ENV_NAME"

echo "✓ 语言环境 $ENV_NAME 安装完成（apt 官方源，无 host root）"
case "$ENV_NAME" in
  cpp)    run_in_proot bash -c 'gcc --version | head -1' ;;
  python) run_in_proot bash -c 'python3 --version' ;;
  java)   run_in_proot bash -c 'javac -version' ;;
esac

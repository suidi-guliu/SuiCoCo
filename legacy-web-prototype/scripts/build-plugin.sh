#!/usr/bin/env bash
# ============================================================
# suicoco 插件打包脚本 —— 将插件目录打包为自有格式 .suicj
# 用法: scripts/build-plugin.sh plugins/lang-c-cpp
# 产物: release/<插件id>.suicj
# ============================================================
set -euo pipefail

SRC_DIR="${1:?用法: build-plugin.sh <插件目录>}"
[ -f "$SRC_DIR/manifest.json" ] || { echo "错误: $SRC_DIR 缺少 manifest.json"; exit 1; }

PLUGIN_ID=$(python3 -c "import json,sys;print(json.load(open('$SRC_DIR/manifest.json'))['id'])")
OUT_DIR="$(cd "$(dirname "$0")/.." && pwd)/release"
OUT="$OUT_DIR/${PLUGIN_ID}.suicj"
mkdir -p "$OUT_DIR"

# .suicj = zip 容器（不兼容其他插件格式；入口必须为 manifest.json + main.js）
(cd "$SRC_DIR" && zip -q -X -r "$OUT" manifest.json . -x "*.DS_Store" -x "*.log")
echo "已打包: $OUT"
unzip -l "$OUT"

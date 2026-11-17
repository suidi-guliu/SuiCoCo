#!/usr/bin/env bash
# ============================================================
# suicoco 插件包校验脚本（CI / 每晚巡检自动化均可调用）
# 校验 release/ 下所有 .suicj 的 zip 结构与 manifest 必填字段
# 用法: scripts/verify-plugin.sh [某.suicj]
# ============================================================
set -euo pipefail

OUT_DIR="$(cd "$(dirname "$0")/.." && pwd)/release"
FILES=()
if [ -n "${1:-}" ]; then FILES=("$1"); else
  while IFS= read -r f; do FILES+=("$f"); done < <(find "$OUT_DIR" -name '*.suicj' | sort)
fi
[ ${#FILES[@]} -gt 0 ] || { echo "没有找到 .suicj 插件包"; exit 0; }

FAIL=0
for f in "${FILES[@]}"; do
  echo "==> 校验 $f"
  if ! unzip -tq "$f" >/dev/null 2>&1; then echo "  [失败] zip 结构损坏"; FAIL=1; continue; fi
  unzip -p "$f" manifest.json >/dev/null 2>&1 || { echo "  [失败] 缺少 manifest.json"; FAIL=1; continue; }
  python3 - "$f" <<'PY' || FAIL=1
import json, sys, zipfile
z = zipfile.ZipFile(sys.argv[1])
m = json.loads(z.read("manifest.json"))
errs = []
for k in ("id", "name", "version", "main", "engines"):
    if k not in m or not m[k]:
        errs.append(f"缺少必填字段 {k}")
if "main" in m and m["main"] not in z.namelist():
    errs.append(f"缺少入口文件 {m['main']}")
if not str(m.get("main", "")).endswith(".js"):
    errs.append("入口 main 必须为 .js")
if "engines" in m and "suicoco" not in m.get("engines", {}):
    errs.append("engines 必须声明 suicoco 版本范围")
for k in m.get("activationEvents", []):
    if not (k == "onStartup" or k.startswith("onLanguage:") or k.startswith("onCommand:")):
        errs.append(f"未知激活事件 {k}")
if errs:
    print("  [失败] " + "; ".join(errs)); sys.exit(1)
print(f"  [通过] id={m['id']} v={m['version']} main={m['main']} 激活事件={m.get('activationEvents')}")
PY
done
[ $FAIL -eq 0 ] && echo "全部校验通过 ✓" || { echo "存在校验失败项 ✗"; exit 1; }

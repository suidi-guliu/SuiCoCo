#!/usr/bin/env bash
# ============================================================
# suicoco 插件包校验脚本（CI / 每晚巡检自动化均可调用）
# 校验 release/ 下所有 .suicj：zip 结构、manifest 必填字段、
# 以及第三方导入沙箱安全项（与 core/extengine/SuicjImportPolicy.kt 对齐）：
#   路径穿越 / 绝对路径 / 反斜杠 / 条目数 / 单文件与总量 / 压缩比 / 入口类型 / 引擎兼容
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
  python3 - "$f" <<'PY' || FAIL=1
import json, sys, zipfile

HOST_VERSION = "0.2.8"
MAX_ENTRIES = 512
MAX_TOTAL = 32 * 1024 * 1024
MAX_SINGLE = 16 * 1024 * 1024
MAX_RATIO = 200

def parse_triple(v):
    core = str(v).strip().split('-')[0].split('+')[0]
    parts = core.split('.')
    if len(parts) < 3:
        return None
    try:
        return tuple(int(x) for x in parts[:3])
    except ValueError:
        return None

def cmp(a, b):
    va, vb = parse_triple(a), parse_triple(b)
    if va is None or vb is None:
        return None
    return (va > vb) - (va < vb)

def engine_ok(rng):
    r = str(rng).strip()
    if not r:
        return False
    if r == '*':
        return True
    if parse_triple(HOST_VERSION) is None:
        return False
    if r.startswith('>='):
        c = cmp(HOST_VERSION, r[2:].strip())
        return c is not None and c >= 0
    if r.startswith('^'):
        base = r[1:].strip()
        h, t = parse_triple(HOST_VERSION), parse_triple(base)
        return h is not None and t is not None and h[0] == t[0] and cmp(HOST_VERSION, base) >= 0
    c = cmp(HOST_VERSION, r)
    return c is not None and c == 0

def unsafe_name(name):
    if not name:
        return "空条目名"
    if '\x00' in name:
        return "含 NUL 字符"
    if any(ord(ch) < 0x20 for ch in name):
        return "含控制字符"
    if name.startswith('/') or name.startswith('\\'):
        return "绝对路径"
    if len(name) >= 2 and name[1] == ':':
        return "含 Windows 盘符"
    if '\\' in name:
        return "含反斜杠"
    norm = name[:-1] if name.endswith('/') else name
    if not norm:
        return "非法条目名"
    for seg in norm.split('/'):
        if seg == '..':
            return "含 '..' 父目录段（目录穿越）"
        if seg == '.':
            return "含 '.' 段"
        if seg == '':
            return "含空路径段（双斜杠）"
    return None

path = sys.argv[1]
z = zipfile.ZipFile(path)
infos = z.infolist()
errs = []

# --- 沙箱安全：解压前条目审查（对齐 SuicjImportPolicy.review） ---
if not infos:
    errs.append("插件包为空")
if len(infos) > MAX_ENTRIES:
    errs.append(f"条目数超限 {len(infos)} > {MAX_ENTRIES}")
total = 0
for i in infos:
    reason = unsafe_name(i.filename)
    if reason:
        errs.append(f"非法条目名「{i.filename}」：{reason}")
    if i.file_size < 0:
        errs.append(f"条目大小非法「{i.filename}」")
        continue
    if not i.is_dir() and i.file_size > MAX_SINGLE:
        errs.append(f"单文件过大「{i.filename}」{i.file_size} > {MAX_SINGLE}")
    if not i.is_dir() and i.file_size >= 1024 and i.compress_size > 0 and i.file_size / i.compress_size > MAX_RATIO:
        errs.append(f"压缩比异常疑似解压炸弹「{i.filename}」")
    total += i.file_size
if total > MAX_TOTAL:
    errs.append(f"解压后总量超限 {total} > {MAX_TOTAL}")

names = z.namelist()
if "manifest.json" not in names:
    errs.append("缺少 manifest.json")
    print("  [失败] " + "; ".join(errs)); sys.exit(1)

try:
    m = json.loads(z.read("manifest.json"))
except Exception as e:
    print(f"  [失败] manifest.json 解析失败：{e}"); sys.exit(1)

for k in ("id", "name", "version", "main", "engines"):
    if k not in m or not m[k]:
        errs.append(f"缺少必填字段 {k}")

main = str(m.get("main", ""))
if not main or unsafe_name(main):
    errs.append(f"入口 main 路径非法「{main}」")
elif not main.lower().endswith(".js"):
    errs.append("入口 main 必须为 .js")
elif main not in names:
    errs.append(f"缺少入口文件 {main}")

eng = m.get("engines", {})
if not isinstance(eng, dict) or "suicoco" not in eng:
    errs.append("engines 必须声明 suicoco 版本范围")
elif not engine_ok(eng["suicoco"]):
    errs.append(f"引擎版本不兼容：suicoco {eng['suicoco']}（当前 {HOST_VERSION}）")

import re
if "id" in m and not re.match(r"^[a-zA-Z0-9][a-zA-Z0-9._-]*\.[a-zA-Z0-9][a-zA-Z0-9._-]*$", str(m["id"])):
    errs.append(f"id 格式非法「{m['id']}」")
if "version" in m and not re.match(r"^\d+\.\d+\.\d+([-+].+)?$", str(m["version"])):
    errs.append(f"version 格式非法「{m['version']}」")

for k in m.get("activationEvents", []):
    if not (k == "onStartup" or str(k).startswith("onLanguage:") or str(k).startswith("onCommand:")):
        errs.append(f"未知激活事件 {k}")

if errs:
    print("  [失败] " + "; ".join(errs)); sys.exit(1)
print(f"  [通过] id={m['id']} v={m['version']} main={m['main']} 引擎={eng.get('suicoco')} 条目={len(infos)} 激活事件={m.get('activationEvents')}")
PY
done
[ $FAIL -eq 0 ] && echo "全部校验通过 ✓" || { echo "存在校验失败项 ✗"; exit 1; }

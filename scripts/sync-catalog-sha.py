#!/usr/bin/env python3
# ============================================================
# sync-catalog-sha.py — 用 release/ 目录下 .suicj 的真实字节回写官方清单的 sha256
# ============================================================
# 背景：build-plugin.sh 用 `zip -r` 打包，zip 会写入各源文件的 mtime 到归档，
# 因此 CI 每次 [skip ci] 重打包后 .suicj 字节（及 SHA256）都会变化；而清单
# release/suicj-catalog.json 的 sha256 字段若写死，就会与产物失配，导致插件
# 下载后的 SHA256 校验必然失败、一键安装全被拒绝。
#
# 本脚本在 CI 打包之后运行，按 release/ 下每个条目的真实文件重算 SHA256 并回写
# 清单（仅在值变化时写盘），确保「清单摘要 == 用户实际下载到的字节」始终成立。
# 使用标准库，零依赖，可在 JVM 之外独立验证。
# ============================================================
import hashlib
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RELEASE_DIR = os.path.join(ROOT, "release")
CATALOG = os.path.join(RELEASE_DIR, "suicj-catalog.json")


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def main() -> int:
    if not os.path.isfile(CATALOG):
        print(f"错误: 缺少清单文件 {CATALOG}", file=sys.stderr)
        return 1
    with open(CATALOG, encoding="utf-8") as f:
        catalog = json.load(f)

    changed = False
    for entry in catalog.get("plugins", []):
        name = entry.get("file", "")
        if not name:
            print("错误: 清单条目缺少 file 字段", file=sys.stderr)
            return 1
        path = os.path.join(RELEASE_DIR, name)
        if not os.path.isfile(path):
            print(f"错误: 清单引用产物不存在: {name}", file=sys.stderr)
            return 1
        actual = sha256_file(path)
        old = entry.get("sha256", "")
        if old != actual:
            print(f"更新 {name}: {old} -> {actual}")
            entry["sha256"] = actual
            changed = True
        else:
            print(f"一致 {name}: {actual}")

    if changed:
        with open(CATALOG, "w", encoding="utf-8") as f:
            json.dump(catalog, f, ensure_ascii=False, indent=2)
            f.write("\n")
        print("清单 SHA256 已按真实产物同步并写盘")
    else:
        print("清单 SHA256 已与产物一致，无需改动")
    return 0


if __name__ == "__main__":
    sys.exit(main())

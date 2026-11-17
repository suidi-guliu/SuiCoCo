#!/usr/bin/env python3
# ============================================================
# embed-plugins.py — 生成 app/js/plugin-bundle.js
# 将 plugins/ 下官方插件源码嵌入网页演示版，作为“官方插件
# 索引”（模拟从 GitHub 插件仓库拉取的 index）。
# 桌面版不使用本文件，直接从仓库下载 .suicj。
# ============================================================
import json, os, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PLUGINS_DIR = os.path.join(ROOT, "plugins")
OUT = os.path.join(ROOT, "app", "js", "plugin-bundle.js")

# 演示用的下载量/评分（真实市场数据来自插件仓库 index.json）
META = {
    "suicj.lang.c-cpp":   {"downloads": 486000, "rating": 4.8},
    "suicj.lang.python":  {"downloads": 712000, "rating": 4.9},
    "suicj.lang.java":    {"downloads": 355000, "rating": 4.6},
    "suicj.example.hello":{"downloads": 12000,  "rating": 5.0},
}

index = []
for name in sorted(os.listdir(PLUGINS_DIR)):
    pdir = os.path.join(PLUGINS_DIR, name)
    if not os.path.isdir(pdir) or not os.path.exists(os.path.join(pdir, "manifest.json")):
        continue
    with open(os.path.join(pdir, "manifest.json"), encoding="utf-8") as f:
        manifest = json.load(f)
    files = {}
    for fn in os.listdir(pdir):
        if fn.endswith(".js") or fn.endswith(".json") or fn.endswith(".md"):
            with open(os.path.join(pdir, fn), encoding="utf-8") as f:
                files[fn] = f.read()
    entry = {
        "source": "official",
        "publisher": manifest.get("publisher", "suicoco-official"),
        "description": manifest.get("description", ""),
        "downloads": META.get(manifest["id"], {}).get("downloads", 1000),
        "rating": META.get(manifest["id"], {}).get("rating", 4.5),
        "manifest": manifest,
        "files": files,
    }
    index.append(entry)

bundle = (
    "// 本文件由 scripts/embed-plugins.py 自动生成，勿手改\n"
    "// 官方插件索引（演示版内嵌；桌面版从 GitHub 插件仓库拉取 index.json 与 .suicj）\n"
    "window.SUICOCO_OFFICIAL_INDEX = " + json.dumps(index, ensure_ascii=False, indent=2) + ";\n"
)
with open(OUT, "w", encoding="utf-8") as f:
    f.write(bundle)
print(f"已生成 {OUT}（{len(index)} 个官方插件）")

#!/usr/bin/env python3
"""suicoco 网页原型无头冒烟测试：加载页面、收集控制台错误、
模拟核心用户路径（打开文件→安装插件→高亮→环境安装→终端→第三方导入）"""
import json, pathlib
from playwright.sync_api import sync_playwright

ROOT = pathlib.Path(__file__).resolve().parent.parent
APP = (ROOT / "app" / "index.html").as_uri()

with sync_playwright() as p:
    browser = p.chromium.launch()
    page = browser.new_page()
    errors = []
    page.on("console", lambda m: (print("[console.%s]" % m.type, m.text[:200]) if m.type in ("error", "warning") else None, errors.append(m.text) if m.type == "error" else None))
    page.on("pageerror", lambda e: (print("[pageerror]", str(e)[:300]), errors.append(str(e))))
    page.goto(APP)
    page.wait_for_timeout(800)
    def log(name, val=""):
        print(f"[step] {name}: {val}")
        page.wait_for_timeout(150)

    results = {}
    # 1. 初始渲染
    results["titlebar"] = page.locator("#titlebar").inner_text()[:12]
    results["official_index"] = page.evaluate("window.SUICOCO_OFFICIAL_INDEX.length")

    # 2. 安装官方语言插件（市场）
    page.click('.act-btn[data-view="plugins"]')
    page.wait_for_timeout(200)
    page.click('button[data-install="suicj.lang.c-cpp"]')
    page.click('button[data-install="suicj.lang.python"]')
    page.click('button[data-install="suicj.lang.java"]')
    page.wait_for_timeout(300)
    results["installed_plugins"] = page.evaluate(
        "PluginEngine.getPlugins().map(x=>x.manifest.id+':'+x.status)")

    # 3. 打开文件 → 语法高亮应来自插件 grammar
    page.click('.act-btn[data-view="explorer"]')
    page.click('.tree-row[data-file="main.cpp"]')
    page.wait_for_timeout(200)
    results["keyword_tokens"] = page.evaluate(
        "document.querySelectorAll('#highlight-layer .tok-keyword').length")
    results["string_tokens"] = page.evaluate(
        "document.querySelectorAll('#highlight-layer .tok-string').length")
    results["lang_mode"] = page.evaluate("document.querySelector('#sb-lang').textContent")

    # 4. 环境未安装时运行任务应报错引导
    page.keyboard.press("Control+Shift+P")
    page.fill("#quick-input input", "任务")
    page.keyboard.press("Enter")
    page.wait_for_timeout(300)
    results["env_guard"] = page.evaluate(
        "document.querySelector('#terminal-view').textContent.includes('语言环境未安装')")

    # 5. 安装向导：ubuntu → cpp
    page.click('.act-btn[data-view="installer"]')
    page.click('#inst-ubuntu button[data-install-env]')
    page.wait_for_timeout(3400)
    results["ubuntu_installed"] = page.evaluate("document.querySelector('#sb-env').textContent")
    page.click('#inst-cpp button[data-install-env]')
    page.wait_for_timeout(2600)

    # 6. 安装环境后运行任务成功
    page.click('.act-btn[data-view="explorer"]')
    page.click('.tree-row[data-file="main.cpp"]')
    page.keyboard.press("Control+Shift+P")
    page.fill("#quick-input input", "任务: C++")
    page.keyboard.press("Enter")
    page.wait_for_timeout(300)
    results["run_output"] = page.evaluate("""(() => {
        const t = document.querySelector('#terminal-view').textContent;
        return t.includes('Hello, suicoco! from suicoco') && t.includes('退出代码 0');
    })()""")

    # 7. 第三方 .suicj 导入（真实 zip 解包）
    page.click('.act-btn[data-view="plugins"]')
    page.wait_for_timeout(200)
    page.set_input_files("#mkt-import-file", str(ROOT / "release" / "suicj.example.hello.suicj"))
    page.wait_for_timeout(500)
    results["third_party_import"] = page.evaluate(
        "PluginEngine.getPlugins().some(x=>x.manifest.id==='suicj.example.hello'&&x.status==='activated')")
    results["toast_hello"] = page.evaluate(
        "document.querySelector('#notifications').textContent.includes('Hello suicoco')")

    # 8. 终端命令
    page.fill("#terminal-input", "suicj list")
    page.keyboard.press("Enter")
    page.fill("#terminal-input", "gcc hello.c")
    page.keyboard.press("Enter")
    page.wait_for_timeout(200)
    results["terminal"] = page.evaluate("""(() => {
        const t = document.querySelector('#terminal-view').textContent;
        return t.includes('suicj.lang.c-cpp') && t.includes('sum = 15');
    })()""")

    # 9. 补全弹层
    print("[debug] 点击 explorer 前状态检查", flush=True)
    page.click('.act-btn[data-view="explorer"]')
    page.wait_for_timeout(300)
    print("[debug] explorer 点击后 sidebar.title =", page.evaluate("document.querySelector('.sidebar-title .vt')?.textContent"), flush=True)
    print("[debug] sidebar 是否 hidden =", page.evaluate("document.querySelector('#sidebar').classList.contains('hidden')"), flush=True)
    print("[debug] 树行数量 =", page.evaluate("document.querySelectorAll('.tree-row').length"), flush=True)
    print("[debug] sidebar-body 前 200 字 =", page.evaluate("document.querySelector('.sidebar-body').innerHTML.slice(0,200)"), flush=True)
    page.click('.tree-row[data-file="app.py"]')
    page.click("#textarea-input", position={"x": 100, "y": 30})
    page.keyboard.type("pri")
    page.wait_for_timeout(300)
    results["suggest"] = page.evaluate(
        "document.querySelector('#suggest-widget').style.display")

    browser.close()

print(json.dumps(results, ensure_ascii=False, indent=2))
print("CONSOLE_ERRORS:", len(errors))
for e in errors[:10]:
    print("  -", e[:200])

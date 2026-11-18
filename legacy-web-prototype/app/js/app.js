/* ============================================================
   suicoco — 主程序（工作台：视图、标签、面板、市场、安装向导）
   ============================================================ */
let app; // 全局引用（编辑器补全回调用）
(() => {
"use strict";

/* ============ 全局状态 ============ */
const S = {
  workspace: DEMO_WORKSPACE,
  files: Object.assign({}, DEMO_WORKSPACE.files), // 虚拟文件系统
  envs: { ubuntu: false, cpp: false, python: false, java: false },
  settings: { pluginRepo: "https://github.com/suidi-guliu/SuiCoco" },
  installing: false,
  selectedFile: null
};
try {
  const savedEnv = localStorage.getItem("suicoco.envs");
  if (savedEnv) Object.assign(S.envs, JSON.parse(savedEnv));
  const savedSet = localStorage.getItem("suicoco.settings");
  if (savedSet) Object.assign(S.settings, JSON.parse(savedSet));
} catch (e) {}

const $ = (sel) => document.querySelector(sel);
const $$ = (sel) => document.querySelectorAll(sel);

/* ============ 宿主接口（暴露给插件引擎） ============ */
window.suicocoHost = {
  notify: (level, title, msg) => toast(level, title, msg),
  statusFlash: (msg, ms) => statusFlash(msg, ms),
  envIsInstalled: (id) => !!S.envs[id],
  requestEnvInstall: (id) => { showActivity("installer"); if (!S.envs[id]) toast("info", "需要语言环境", "请在安装向导中安装对应语言环境: " + id); },
  getDocument: (f) => { const ed = SuicjEditor.find(f); return ed ? { content: ed.getContent() } : null; }
};

/* ============ 通知 ============ */
function toast(level, title, msg, actions) {
  const el = document.createElement("div");
  el.className = "toast" + (level === "warn" ? " warn" : level === "error" ? " error" : "");
  const icons = { info: "ⓘ", warn: "⚠", error: "✕" };
  el.innerHTML = `<div class="ticon">${icons[level] || "ⓘ"}</div>
    <div class="tbody"><div class="ttitle">${escapeH(title)}</div><div>${escapeH(msg || "")}</div>
    <div class="tbtns"></div></div>
    <div class="tx"><button title="关闭">✕</button></div>`;
  if (actions && actions.length) {
    const btns = el.querySelector(".tbtns");
    actions.forEach(a => {
      const b = document.createElement("button");
      b.className = "btn small primary"; b.textContent = a.label;
      b.onclick = () => { a.run(); el.remove(); };
      btns.appendChild(b);
    });
  } else el.querySelector(".tbtns").style.display = "none";
  el.querySelector(".tx button").onclick = () => el.remove();
  $("#notifications").appendChild(el);
  setTimeout(() => el.remove(), 9000);
}
function statusFlash(msg, ms) {
  const el = document.createElement("div");
  el.className = "sb-item"; el.style.color = "#e7e7e7"; el.textContent = msg;
  $("#statusbar").insertBefore(el, $("#statusbar").children[1]);
  setTimeout(() => el.remove(), ms);
}

/* ============ 活动栏 / 侧边栏 ============ */
const VIEWS = ["explorer", "search", "scm", "run", "plugins", "installer"];
let activeView = "explorer";
function showActivity(view) {
  activeView = view;
  $$(".act-btn[data-view]").forEach(b => b.classList.toggle("active", b.dataset.view === view));
  $("#sidebar").classList.remove("hidden");
  const titles = { explorer: "资源管理器", search: "搜索", scm: "源代码管理", run: "运行和调试", plugins: "插件市场", installer: "系统与环境安装" };
  $(".sidebar-title .vt").textContent = titles[view];
  renderSidebar();
}
function renderSidebar() {
  const body = $(".sidebar-body");
  const actionsEl = $(".view-actions");
  actionsEl.innerHTML = "";
  const btn = (title, html, onclick) => {
    const b = document.createElement("button");
    b.title = title; b.innerHTML = html; b.onclick = onclick;
    actionsEl.appendChild(b);
    return b;
  };
  switch (activeView) {
    case "explorer": body.innerHTML = explorerHtml(); bindExplorer(); break;
    case "search": body.innerHTML = searchHtml(); bindSearch(); break;
    case "scm": body.innerHTML = scmHtml(); break;
    case "run": body.innerHTML = runHtml(); bindRun(); break;
    case "plugins": body.innerHTML = mktHtml(); bindMkt(); break;
    case "installer": body.innerHTML = installerHtml(); bindInstaller(); break;
  }
}
/* ---- 资源管理器 ---- */
function explorerHtml() {
  const rows = Object.keys(S.files).map(f =>
    `<div class="tree-row ${S.selectedFile === f ? "selected" : ""}" data-file="${escapeH(f)}">
       <span class="indent">　</span>
       <span class="tree-icon">${fileIcon(f)}</span>
       <span class="fname">${escapeH(f)}</span>
     </div>`).join("");
  return `<div class="explorer-section">
      <div class="explorer-section-header"><span class="chev">▾</span>${escapeH(S.workspace.name)}</div>
      <div class="file-tree">${rows}</div>
    </div>`;
}
function fileIcon(f) {
  if (/\.(c|h)$/i.test(f)) return "🇨";
  if (/\.cpp$|\.cc$|\.hpp$/i.test(f)) return "🐞";
  if (/\.py$/i.test(f)) return "🐍";
  if (/\.java$/i.test(f)) return "☕";
  if (/\.md$/i.test(f)) return "📒";
  return "📄";
}
function bindExplorer() {
  $$(".tree-row[data-file]").forEach(r => r.onclick = () => {
    const f = r.dataset.file;
    S.selectedFile = f;
    openFile(f);
    renderSidebar();
  });
}
/* ---- 搜索 ---- */
function searchHtml() {
  return `<div class="search-input-wrap"><input id="search-box" placeholder="搜索"></div>
          <div class="search-result" id="search-result"></div>`;
}
function bindSearch() {
  const input = $("#search-box");
  input.oninput = () => {
    const q = input.value.trim();
    const box = $("#search-result");
    if (!q) { box.innerHTML = ""; return; }
    let html = "", total = 0;
    for (const f in S.files) {
      S.files[f].split("\n").forEach((line, i) => {
        if (line.toLowerCase().includes(q.toLowerCase())) {
          total++;
          html += `<div class="hit" data-file="${escapeH(f)}" data-line="${i}">${escapeH(f)}:${i + 1} — ${highlight(line, q)}</div>`;
        }
      });
    }
    box.innerHTML = `<div style="color:#8b8b8b;margin-bottom:4px">${total} 个结果</div>` + html;
    box.querySelectorAll(".hit").forEach(h => h.onclick = () => {
      openFile(h.dataset.file);
      const ed = SuicjEditor.currentEd();
      if (ed) { const pos = S.files[h.dataset.file].split("\n").slice(0, +h.dataset.line).join("\n").length + 1; ed.taEl.focus(); ed.taEl.setSelectionRange(pos, pos); }
    });
  };
  input.focus();
}
function highlight(line, q) {
  const idx = line.toLowerCase().indexOf(q.toLowerCase());
  return escapeH(line.slice(0, idx)) + "<mark>" + escapeH(line.slice(idx, idx + q.length)) + "</mark>" + escapeH(line.slice(idx + q.length));
}
/* ---- 源代码管理（静态演示） ---- */
function scmHtml() {
  return `<div style="padding:10px 14px;color:#8b8b8b;font-size:12px;line-height:1.7">
      当前分支: <b style="color:#e7e7e7">main</b><br>工作区干净，无待提交更改<br><br>
      <span class="badge-tag">suicoco</span> 插件驱动版本控制支持将在阶段5接入。
    </div>`;
}
/* ---- 运行和调试 ---- */
function runHtml() {
  const tasks = PluginEngine.getTasksFor();
  const ed = SuicjEditor.currentEd();
  const langId = ed ? (PluginEngine.getLanguage(ed.file) || {}).id : null;
  const html = tasks.length
    ? `<div class="explorer-section"><div class="explorer-section-header"><span class="chev">▾</span>运行任务</div>
        <div class="file-tree">` + tasks.map(t =>
        `<div class="tree-row" data-task="${t.id}"><span class="indent">　</span><span class="tree-icon">▶</span><span class="fname">${escapeH(t.label)}</span></div>`).join("") +
        `</div></div>`
    : `<div style="padding:10px 14px;color:#8b8b8b;font-size:12px;line-height:1.7">尚未注册任何运行任务。<br>安装语言插件后即可“编译并运行”当前文件。</div>`;
  setTimeout(() => {
    $$(".tree-row[data-task]").forEach(r => r.onclick = () => {
      const t = PluginEngine.getTasksFor().find(x => x.id === r.dataset.task);
      if (t) runTask(t);
    });
  });
  return html + (langId ? `<div style="padding:4px 14px;color:#6e6e6e;font-size:11px">当前文件语言: ${langId}</div>` : "");
}
/* ---- 插件市场 ---- */
function mktHtml() {
  const installed = PluginEngine.getPlugins();
  let html = `
    <div class="mkt-search"><input id="mkt-q" placeholder="在插件市场中搜索"></div>
    <div class="mkt-actions">
      <button class="btn primary" id="mkt-fetch">⟳ 从 GitHub 仓库获取官方插件</button>
      <button class="btn" id="mkt-import">⬇ 导入第三方插件 (.suicj)…</button>
      <input type="file" id="mkt-import-file" accept=".suicj" style="display:none">
    </div>
    <div class="mkt-note">官方插件来源：GitHub 仓库（设置中可改）<br>第三方插件仅支持 .suicj 自有格式，不兼容其他插件格式。</div>`;
  html += installed.map(p => pluginCardHtml(p, true)).join("");
  html += `<div class="explorer-section"><div class="explorer-section-header" style="margin-top:6px"><span class="chev">▾</span>官方插件（在线）</div></div>`;
  const installedIds = new Set(installed.map(p => p.manifest.id));
  html += (window.SUICOCO_OFFICIAL_INDEX || [])
    .filter(p => !installedIds.has(p.manifest.id))
    .map(p => {
      const requiresEnv = p.manifest.requiresEnv;
      return `<div class="plugin-card">
        <div class="pname">${escapeH(p.manifest.name)} <span class="badge-tag ${p.source === "official" ? "ok" : ""}">${p.publisher || "官方"}</span>${requiresEnv ? `<span class="badge-tag env">需语言环境: ${requiresEnv}</span>` : ""}</div>
        <div class="pdesc">${escapeH(p.description || "")}</div>
        <div class="pmeta"><span>v${p.manifest.version}</span><span>⬇ ${(p.downloads / 1000).toFixed(1)}K</span><span>★ ${p.rating || "-"}</span></div>
        <div class="pops"><button class="btn small primary" data-install="${p.manifest.id}">安装</button></div>
      </div>`;
    }).join("") || `<div class="mkt-note">（已是最新，全部官方插件均已安装）</div>`;
  return html;
}
function pluginCardHtml(p, installed) {
  const m = p.manifest;
  return `<div class="plugin-card">
    <div class="pname">${escapeH(m.name)}
      ${p.source === "third" ? `<span class="badge-tag third">第三方</span>` : `<span class="badge-tag ok">官方</span>`}
      <span class="badge-tag">${p.status === "activated" ? "已激活" : "已安装"}</span>
    </div>
    <div class="pdesc">${escapeH(m.description || "")}</div>
    <div class="pmeta"><span>v${m.version}</span><span>${escapeH(m.publisher || "")}</span></div>
    ${m.requiresEnv ? `<div class="pmeta"><span class="badge-tag env">需语言环境: ${m.requiresEnv}</span></div>` : ""}
    <div class="pops">
      ${m.requiresEnv && !S.envs[m.requiresEnv] ? `<button class="btn small" data-env="${m.requiresEnv}">安装依赖环境</button>` : ""}
      <button class="btn small" data-uninstall="${m.id}">卸载</button>
    </div>
  </div>`;
}
function bindMkt() {
  $("#mkt-fetch").onclick = async () => {
    // 桌面版：从 settings.pluginRepo 拉取 index.json + .suicj 并安装
    toast("info", "官方插件源", "正在从仓库获取索引: " + S.settings.pluginRepo + " （演示环境使用内置索引）");
    termLog("git clone " + S.settings.pluginRepo + " （演示环境：使用内置官方索引）", "dim");
    setTimeout(renderSidebar, 600);
  };
  $("#mkt-import").onclick = () => $("#mkt-import-file").click();
  $("#mkt-import-file").onchange = async (e) => {
    const file = e.target.files[0];
    if (!file) return;
    if (!/\.suicj$/i.test(file.name)) { toast("error", "格式错误", "仅支持导入 .suicj 自有插件格式"); return; }
    try {
      const buf = await file.arrayBuffer();
      const p = await PluginEngine.importSuicj(buf);
      toast("info", "第三方插件已导入", `${p.manifest.name} v${p.manifest.version}（来源：本地 .suicj 文件）`);
      termLog("[插件] 导入第三方插件成功: " + file.name, "ok");
      SuicjEditor.refreshAll(); renderSidebar(); refreshStatusbar();
    } catch (err) {
      toast("error", "导入失败", String(err.message || err));
      termLog("[插件] 导入失败: " + (err.message || err), "err");
    }
  };
  $$("#mkt-q") && ($("#mkt-q").oninput = () => {
    const q = $("#mkt-q").value.toLowerCase();
    $$(".sidebar-body .plugin-card").forEach(c => { c.style.display = c.textContent.toLowerCase().includes(q) ? "" : "none"; });
  });
  $$("button[data-install]").forEach(b => b.onclick = () => {
    const src = window.SUICOCO_OFFICIAL_INDEX.find(p => p.manifest.id === b.dataset.install);
    try {
      const p = PluginEngine.installFromSource(src, "official");
      const env = p.manifest.requiresEnv;
      if (env && !S.envs[env]) {
        toast("warn", "插件已安装", `${p.manifest.name} 需要语言环境「${env}」，建议前往「系统与环境安装」安装。`,
          [{ label: "去安装环境", run: () => showActivity("installer") }]);
      } else toast("info", "插件已安装", p.manifest.name + " v" + p.manifest.version);
      termLog("[插件] 安装官方插件: " + p.manifest.id, "ok");
      SuicjEditor.refreshAll(); renderSidebar(); refreshStatusbar();
    } catch (err) { toast("error", "安装失败", String(err.message || err)); }
  });
  $$("button[data-uninstall]").forEach(b => b.onclick = () => {
    PluginEngine.uninstall(b.dataset.uninstall);
    toast("info", "已卸载插件", b.dataset.uninstall);
    SuicjEditor.refreshAll(); renderSidebar(); refreshStatusbar();
  });
  $$("button[data-env]").forEach(b => b.onclick = () => showActivity("installer"));
}

/* ---- 安装向导 ---- */
const INSTALL_TARGETS = {
  ubuntu: { title: "Ubuntu 精简系统环境", env: "ubuntu", desc: "debootstrap --variant=minbase 构建的最小 Ubuntu 用户态，作为软件运行底座与语言环境容器。", src: "官方源 http://archive.ubuntu.com/ubuntu（apt）" },
  cpp: { title: "C / C++ 语言环境", env: "cpp", desc: "安装 gcc / g++ / make / gdb 与 libc 开发头文件。", src: "官方源: build-essential, gdb (apt)" },
  python: { title: "Python 语言环境", env: "python", desc: "安装 python3 / pip / venv。", src: "官方源: python3, python3-pip (apt)" },
  java: { title: "Java 语言环境", env: "java", desc: "安装 OpenJDK 17 与 javac 编译器。", src: "官方源: openjdk-17-jdk (apt)" }
};
function installerHtml() {
  let html = `<div class="mkt-note" style="padding:8px 12px 2px 12px">软件本体不内置系统与语言环境，全部通过图形化安装器从官方来源下载。架构层次：<br>内核 → Ubuntu 精简系统 → 软件本体 → 自有插件 → 语言环境</div>`;
  for (const [key, t] of Object.entries(INSTALL_TARGETS)) {
    const done = S.envs[key];
    html += `<div class="inst-step" id="inst-${key}">
      <h4>${t.title} ${done ? `<span class="badge-tag ok">已安装</span>` : ""}</h4>
      <div class="desc">${t.desc}</div>
      <div class="src">来源：${t.src}</div>
      <div class="progress" style="display:none"><div class="bar"></div></div>
      <div class="inst-log"></div>
      ${done
        ? `<div class="pops"><button class="btn small" data-reinstall="${key}">重新安装</button><button class="btn small" data-remove="${key}">卸载</button></div>`
        : `<div class="pops"><button class="btn small primary" data-install-env="${key}">安装</button></div>`}
    </div>`;
  }
  return html;
}
const INSTALL_LOGS = {
  ubuntu: [
    "$ debootstrap --variant=minbase noble ~/.suicoco/envs/ubuntu-base http://archive.ubuntu.com/ubuntu",
    "I: Retrieving InRelease …", "I: Retrieving Packages … 2143 个包",
    "I: Extracting base system …", "I: Base system installed successfully",
    "✓ Ubuntu 精简系统环境安装完成（~180MB）"
  ],
  cpp: ["$ apt install -y build-essential gdb", "Reading package lists… Done", "Setting up gcc-13, g++-13, make, gdb …", "✓ C/C++ 语言环境安装完成 (gcc 13.2.0)"],
  python: ["$ apt install -y python3 python3-pip python3-venv", "Reading package lists… Done", "Setting up python3 (3.12) …", "✓ Python 语言环境安装完成 (Python 3.12.3)"],
  java: ["$ apt install -y openjdk-17-jdk", "Reading package lists… Done", "Setting up openjdk-17-jdk-headless …", "✓ Java 语言环境安装完成 (javac 17.0.x)"]
};
function bindInstaller() {
  $$("button[data-install-env]").forEach(b => b.onclick = () => installEnv(b.dataset.installEnv));
  $$("button[data-reinstall]").forEach(b => b.onclick = () => installEnv(b.dataset.reinstall));
  $$("button[data-remove]").forEach(b => b.onclick = () => {
    const k = b.dataset.remove;
    if (k === "ubuntu" && (S.envs.cpp || S.envs.python || S.envs.java)) {
      toast("warn", "无法卸载", "请先卸载依赖该系统的语言环境"); return;
    }
    S.envs[k] = false; saveEnvs();
    toast("info", "已卸载", INSTALL_TARGETS[k].title);
    termLog("[环境] 卸载: " + INSTALL_TARGETS[k].title, "dim");
    renderSidebar(); refreshStatusbar();
  });
}
function installEnv(key) {
  if (key !== "ubuntu" && !S.envs.ubuntu) {
    toast("warn", "缺少系统底座", "请先安装 Ubuntu 精简系统环境，再安装语言环境。", [{ label: "去安装", run: () => $("#inst-ubuntu button[data-install-env]").click() }]);
    return;
  }
  if (S.installing) { toast("warn", "安装进行中", "请等待当前安装任务完成"); return; }
  S.installing = true;
  const t = INSTALL_TARGETS[key];
  const step = $("#inst-" + key);
  const bar = step.querySelector(".bar");
  const logEl = step.querySelector(".inst-log");
  step.querySelector(".progress").style.display = "";
  logEl.style.display = "block"; logEl.innerHTML = "";
  termLog("[环境] 开始安装: " + t.title + "（来源: 官方源）");
  const lines = INSTALL_LOGS[key];
  let i = 0;
  const timer = setInterval(() => {
    if (i < lines.length) {
      const line = lines[i++];
      const div = document.createElement("div");
      div.className = line.startsWith("✓") ? "ok" : "";
      div.textContent = line;
      logEl.appendChild(div); logEl.scrollTop = logEl.scrollHeight;
      bar.style.width = Math.round(i / lines.length * 100) + "%";
      termLog("[环境] " + line, line.startsWith("✓") ? "ok" : "dim");
    } else {
      clearInterval(timer);
      S.envs[key] = true; S.installing = false; saveEnvs();
      toast("info", "安装完成", t.title);
      if (key === "ubuntu") toast("info", "建议", "现在可以安装 C/C++、Python 或 Java 语言环境了");
      renderSidebar(); refreshStatusbar();
    }
  }, 420);
}
function saveEnvs() { try { localStorage.setItem("suicoco.envs", JSON.stringify(S.envs)); } catch (e) {} }

/* ============ 设置视图（活动栏底部齿轮 → 打开设置） ============ */
function settingsHtml() {
  return `<div class="settings-view">
    <label>官方插件 GitHub 仓库</label>
    <input id="set-repo" value="${escapeH(S.settings.pluginRepo)}">
    <div class="hint">插件市场的“获取官方插件”从这里下载。软件内仅开放官方下载入口与第三方导入按键。</div>
    <label>下载校验</label>
    <div class="hint">.suicj 为自有插件格式（zip 容器），导入时校验 manifest 必填字段与引擎版本，不兼容其他插件格式。</div>
    <div style="margin-top:14px"><button class="btn primary" id="set-save">保存设置</button></div>
  </div>`;
}

/* ============ 编辑器标签 / 文件打开 ============ */
function openFile(f) {
  const ed = SuicjEditor.open(f, S.files[f]);
  renderTabs();
  const lang = PluginEngine.getLanguage(f);
  if (lang) PluginEngine.ensureActivatedForLanguage(lang.id);
  app.updateStatusCursor(ed.cursor);
  refreshStatusbar();
}
function renderTabs() {
  const bar = $("#tabbar");
  bar.innerHTML = "";
  const cur = SuicjEditor.currentEd();
  SuicjEditor.openFiles().forEach(f => {
    const ed = SuicjEditor.find(f);
    const tab = document.createElement("div");
    tab.className = "tab" + (cur === ed ? " active" : "");
    tab.innerHTML = `<span class="tlabel">${escapeH(f)}</span>
      <span class="dirty">${ed.dirty ? "●" : ""}</span>
      <button class="tclose">✕</button>`;
    tab.onclick = (e) => { if (!e.target.classList.contains("tclose")) { SuicjEditor.activate(f); renderTabs(); refreshStatusbar(); } };
    tab.querySelector(".tclose").onclick = () => { if (SuicjEditor.close(f)) { renderTabs(); refreshStatusbar(); } };
    bar.appendChild(tab);
  });
  // 面包屑
  const bc = $("#breadcrumbs");
  bc.innerHTML = cur
    ? `<span>${escapeH(S.workspace.name)}</span><span class="crumb-sep">›</span><span style="color:#ccc">${escapeH(cur.file)}</span>` +
      (cur.langName ? `<span class="crumb-sep">›</span><span>${cur.langName}</span>` : "")
    : "";
}

/* ============ 面板：问题 / 输出 / 终端 ============ */
let panelView = "terminal";
function showPanel(view) {
  panelView = view;
  $("#panel").classList.remove("hidden");
  $$(".panel-tab").forEach(t => t.classList.toggle("active", t.dataset.view === view));
  $("#problems-view").style.display = view === "problems" ? "" : "none";
  $("#output-view").style.display = view === "output" ? "" : "none";
  $("#terminal-view").style.display = view === "terminal" ? "" : "none";
  if (view === "terminal") $("#terminal-input").focus();
  if (view === "problems") renderProblems();
}
function renderProblems() {
  const list = PluginEngine.getProblems();
  $("#problems-view").innerHTML = list.length
    ? list.map(p => `<div class="problem-row"><span>${p.severity === "error" ? "✕" : "⚠"}</span><span>${escapeH(p.message)}</span><span class="psrc">${escapeH(p.file || "")}${p.line ? ":" + p.line : ""}</span></div>`).join("")
    : `<div style="color:#8b8b8b">未检测到问题。</div>`;
  const errs = list.filter(p => p.severity === "error").length;
  const warns = list.length - errs;
  $("#sb-err").textContent = "✕ " + errs;
  $("#sb-warn").textContent = "⚠ " + warns;
}
/* ---- 终端 ---- */
function termLog(text, cls) {
  const el = $("#terminal-view");
  const div = document.createElement("div");
  div.className = "t-line" + (cls ? " " + cls : "");
  div.textContent = text;
  el.insertBefore(div, $("#terminal-input-row"));
  el.scrollTop = el.scrollHeight;
}
function termCmd(text) {
  const el = $("#terminal-view");
  const div = document.createElement("div");
  div.className = "t-line cmd";
  div.textContent = "suicoco@demo:~$ " + text;
  el.insertBefore(div, $("#terminal-input-row"));
  el.scrollTop = el.scrollHeight;
}
function runTask(t) {
  showPanel("terminal");
  termCmd(t.command);
  const ed = SuicjEditor.currentEd();
  if (!ed) { termLog("没有打开的文件", "err"); return; }
  const envMap = { c: "cpp", cpp: "cpp", python: "python", java: "java" };
  const lang = PluginEngine.getLanguage(ed.file);
  const envId = lang && envMap[lang.id];
  if (envId && !S.envs[envId]) {
    termLog("错误: 语言环境未安装（" + envId + "）。请先在「系统与环境安装」中安装。", "err");
    toast("warn", "缺少语言环境", "运行任务需要 " + envId + " 环境", [{ label: "去安装", run: () => showActivity("installer") }]);
    return;
  }
  const out = DEMO_RUN_OUTPUT[ed.file];
  if (out) out.forEach(l => termLog(l));
  else termLog("（已执行: " + t.command + "）", "dim");
  termLog("任务完成，退出代码 0", "ok");
}
function terminalExec(cmd) {
  termCmd(cmd);
  const [c, ...args] = cmd.trim().split(/\s+/);
  const ed = SuicjEditor.currentEd();
  switch (c) {
    case "help":
      termLog("可用命令:", "dim");
      termLog("  help                 显示帮助");
      termLog("  suicj list            查看已安装插件");
      termLog("  suicj install <id>   安装官方插件");
      termLog("  suicj run <task>     运行插件任务（或直接 gcc/g++/python/java 文件名）");
      termLog("  env install <name>  安装语言环境 (cpp/python/java)");
      termLog("  env list            查看已安装环境");
      termLog("  clear               清屏");
      break;
    case "clear": $("#terminal-view").querySelectorAll(".t-line").forEach(d => d.remove()); break;
    case "suicj":
      if (args[0] === "list") {
        const ps = PluginEngine.getPlugins();
        if (!ps.length) termLog("尚未安装任何插件。打开插件市场安装官方语言插件。", "dim");
        ps.forEach(p => termLog(`${p.status === "activated" ? "●" : "○"} ${p.manifest.id}  v${p.manifest.version}  [${p.source === "third" ? "第三方" : "官方"}] ${p.status === "activated" ? "已激活" : "已安装"}`));
      } else if (args[0] === "install" && args[1]) {
        const src = (window.SUICOCO_OFFICIAL_INDEX || []).find(p => p.manifest.id === args[1]);
        if (!src) { termLog("官方索引中未找到插件: " + args[1], "err"); break; }
        try {
          PluginEngine.installFromSource(src, "official");
          termLog("已安装并激活: " + args[1], "ok");
          SuicjEditor.refreshAll(); renderSidebar(); refreshStatusbar();
        } catch (e) { termLog("安装失败: " + e.message, "err"); }
      } else if (args[0] === "run" && args[1]) {
        const t = PluginEngine.getTasksFor().find(t => t.id === args[1]);
        if (t) runTask(t); else termLog("未找到任务: " + args[1], "err");
      } else termLog("用法: suicj list | suicj install <id> | suicj run <task>", "dim");
      break;
    case "env":
      if (args[0] === "install" && args[1] && INSTALL_TARGETS[args[1]]) {
        if (args[1] !== "ubuntu" && !S.envs.ubuntu) { termLog("请先安装 ubuntu 精简系统底座（图形向导或 env install ubuntu）", "err"); break; }
        S.envs[args[1]] = true; saveEnvs(); renderSidebar(); refreshStatusbar();
        termLog("✓ " + INSTALL_TARGETS[args[1]].title + " 安装完成（官方源）", "ok");
      } else if (args[0] === "list") {
        Object.entries(S.envs).forEach(([k, v]) => termLog((v ? "✓ " : "✗ ") + INSTALL_TARGETS[k].title));
      } else termLog("用法: env install <ubuntu|cpp|python|java> | env list", "dim");
      break;
    case "gcc": case "g++": case "python": case "python3": case "javac": case "java": {
      const fileArg = args.find(a => S.files[a] || (ed && a.includes(ed.file)));
      const envMap = { gcc: "cpp", "g++": "cpp", python: "python", python3: "python", javac: "java", java: "java" };
      const target = fileArg || (ed ? ed.file : null);
      if (!target) { termLog("没有可运行的目标文件", "err"); break; }
      if (!S.envs[envMap[c]]) { termLog(c + ": 未安装。请先在「系统与环境安装」中安装对应语言环境（官方源）。", "err"); break; }
      const out = DEMO_RUN_OUTPUT[target];
      if (c === "javac") termLog("（编译成功）", "ok");
      else if (out) out.forEach(l => termLog(l));
      else termLog("（已执行 " + c + " " + target + "）", "dim");
      break;
    }
    case "ls": Object.keys(S.files).forEach(f => termLog(f)); break;
    case "cat": if (S.files[args[0]]) S.files[args[0]].split("\n").forEach(l => termLog(l)); else termLog("cat: " + args[0] + ": 没有那个文件", "err"); break;
    default:
      termLog(c + ": 命令未找到。输入 help 查看可用命令。", "err");
  }
}

/* ============ 快速输入：Ctrl+P / Ctrl+Shift+P ============ */
let qiMode = null, qiItems = [], qiSel = 0;
function openQuickInput(mode) {
  qiMode = mode;
  const input = $("#quick-input input");
  $("#quick-input").style.display = "block";
  input.value = "";
  input.placeholder = mode === "files" ? "按名称搜索文件（输入文件名）" : "输入命令名称";
  input.focus();
  qiUpdate("");
}
function closeQuickInput() { $("#quick-input").style.display = "none"; qiMode = null; }
function qiUpdate(q) {
  const list = $("#quick-input .qi-list");
  qiItems = []; qiSel = 0;
  if (qiMode === "files") {
    qiItems = Object.keys(S.files).filter(f => f.toLowerCase().includes(q.toLowerCase()))
      .map(f => ({ icon: fileIcon(f), label: f, run: () => openFile(f) }));
  } else {
    const builtins = [
      { icon: "⌨", label: "首选项: 打开设置", key: "", run: () => showActivity("settings") },
      { icon: "◫", label: "视图: 切换侧边栏可见性", key: "Ctrl+B", run: () => $("#sidebar").classList.toggle("hidden") },
      { icon: "▤", label: "视图: 切换底部面板", key: "Ctrl+`", run: () => $("#panel").classList.toggle("hidden") },
      { icon: "◫", label: "视图: 插件市场", key: "", run: () => showActivity("plugins") },
      { icon: "▣", label: "suicoco: 系统与环境安装", key: "", run: () => showActivity("installer") },
      { icon: "🗎", label: "文件: 保存", key: "Ctrl+S", run: () => { const e = SuicjEditor.currentEd(); if (e) e.save(); } },
      { icon: "✕", label: "查看: 问题", key: "Ctrl+Shift+M", run: () => showPanel("problems") }
    ];
    const plugins = PluginEngine.getCommands().map(c => ({ icon: "🧩", label: c.label + " (" + c.id + ")", key: "", run: () => c.run() }));
    const taskItems = PluginEngine.getTasksFor().map(t => ({ icon: "▶", label: "任务: " + t.label, key: "", run: () => runTask(t) }));
    const all = builtins.concat(plugins, taskItems);
    qiItems = all.filter(i => i.label.toLowerCase().includes(q.toLowerCase()));
  }
  list.innerHTML = qiItems.map((i, idx) =>
    `<div class="qi-item ${idx === 0 ? "focused" : ""}" data-idx="${idx}"><span class="qi-icon">${i.icon || ""}</span><span>${escapeH(i.label)}</span>${i.key ? `<span class="qi-key">${i.key}</span>` : ""}</div>`).join("") ||
    `<div class="qi-item dim"><span>没有匹配项</span></div>`;
  list.querySelectorAll(".qi-item[data-idx]").forEach(el => el.onclick = () => qiRun(+el.dataset.idx));
}
function qiRun(idx) {
  const it = qiItems[idx];
  if (it) { closeQuickInput(); it.run(); }
}

/* ============ 状态栏 ============ */
function refreshStatusbar() {
  const envs = Object.entries(S.envs).filter(([, v]) => v).map(([k]) => k).join(" ");
  $("#sb-env").textContent = envs ? "环境: " + envs : "环境: 未安装";
  $("#sb-plugins").textContent = "🧩 " + PluginEngine.getPlugins().length;
  const ed = SuicjEditor.currentEd();
  $("#sb-lang").textContent = ed ? ed.langName : "纯文本";
  renderTabs();
}
app = {
  updateStatusCursor(cur) {
    $("#sb-ln").textContent = "行 " + cur.ln + "，列 " + cur.col;
  },
  editorKeydown(e, ed) {
    if (e.ctrlKey && e.key === " ") { e.preventDefault(); showSuggest(ed, true); }
    else if (e.key === "Escape") { hideSuggest(); }
  },
  suggestOnType(ed) { showSuggest(ed, false); }
};
/* ---- 补全（IntelliSense 简版） ---- */
function showSuggest(ed, force) {
  const lang = PluginEngine.getLanguage(ed.file);
  if (!lang) return;
  const pos = ed.taEl.selectionStart;
  const before = ed.taEl.value.slice(0, pos);
  const m = /([A-Za-z_][A-Za-z0-9_]*)$/.exec(before);
  const prefix = m ? m[1] : "";
  if (!force && prefix.length < 2) { hideSuggest(); return; }
  let items = PluginEngine.getCompletions(lang.id, prefix)
    .filter(i => i.label.toLowerCase().startsWith(prefix.toLowerCase()) || force)
    .slice(0, 12);
  if (!items.length) { hideSuggest(); return; }
  const w = $("#suggest-widget");
  const rect = ed.taEl.getBoundingClientRect();
  const lineIdx = before.split("\n").length - 1;
  const col = before.length - before.lastIndexOf("\n") - 1;
  w.style.left = Math.min(rect.left + 12 + col * 8.4, window.innerWidth - 440) + "px";
  w.style.top = (rect.top + 6 + (lineIdx + 1) * 19 - ed.taEl.scrollTop) + "px";
  w.innerHTML = `<div class="shead">${items.length} 个建议 (${lang.name})</div>` +
    items.map((i, idx) => `<div class="sitem ${idx === 0 ? "focused" : ""}" data-idx="${idx}"><span class="sicon">${i.kind === "keyword" ? "🔑" : i.kind === "snippet" ? "▤" : "𝑓"}</span><span class="slabel">${escapeH(i.label)}</span><span class="sdetail">${escapeH(i.detail || "")}</span></div>`).join("");
  w.style.display = "block";
  w.querySelectorAll(".sitem").forEach(el => el.onclick = () => acceptSuggest(ed, items, +el.dataset.idx, prefix));
  ed._suggest = { items, prefix };
  ed._suggestSel = 0;
}
function hideSuggest() { $("#suggest-widget").style.display = "none"; }
function acceptSuggest(ed, items, idx, prefix) {
  const it = items[idx];
  if (!it) return;
  const ta = ed.taEl;
  const s = ta.selectionStart;
  const start = s - prefix.length;
  const text = it.insert || it.label;
  ta.value = ta.value.slice(0, start) + text + ta.value.slice(s);
  ta.selectionStart = ta.selectionEnd = start + text.length;
  ed.content = ta.value; ed.setDirty(true); ed.refresh();
  hideSuggest(); ta.focus();
}

/* ============ 工具 ============ */
function escapeH(s) { return String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;"); }

/* ============ 启动 ============ */
function boot() {
  SuicjEditor.init($("#editor-host"), {
    onEditorChange: () => renderTabs(),
    onSave: (ed) => { S.files[ed.file] = ed.getContent(); renderSidebar(); }
  });

  // 活动栏
  $$(".act-btn[data-view]").forEach(b => b.onclick = () => showActivity(b.dataset.view));
  $("#act-settings").onclick = () => { activeView = "settings"; showSettingsView(); };
  function showSettingsView() {
    $$(".act-btn[data-view]").forEach(x => x.classList.remove("active"));
    $("#sidebar").classList.remove("hidden");
    $(".sidebar-title .vt").textContent = "设置";
    $(".sidebar-body").innerHTML = settingsHtml();
    $("#set-save").onclick = () => {
      S.settings.pluginRepo = $("#set-repo").value.trim() || S.settings.pluginRepo;
      try { localStorage.setItem("suicoco.settings", JSON.stringify(S.settings)); } catch (e) {}
      toast("info", "设置已保存", "官方插件仓库: " + S.settings.pluginRepo);
    };
  }

  // 面板
  $$(".panel-tab").forEach(t => t.onclick = () => showPanel(t.dataset.view));
  $("#panel-close").onclick = () => $("#panel").classList.add("hidden");
  $("#terminal-input").addEventListener("keydown", (e) => {
    if (e.key === "Enter") { terminalExec(e.target.value); e.target.value = ""; }
  });

  // 全局快捷键
  document.addEventListener("keydown", (e) => {
    const mod = e.ctrlKey || e.metaKey;
    if (mod && e.shiftKey && e.key.toLowerCase() === "p") { e.preventDefault(); openQuickInput("commands"); }
    else if (mod && !e.shiftKey && e.key.toLowerCase() === "p") { e.preventDefault(); openQuickInput("files"); }
    else if (mod && e.key === "`") { e.preventDefault(); $("#panel").classList.toggle("hidden"); }
    else if (mod && e.key.toLowerCase() === "b") { e.preventDefault(); $("#sidebar").classList.toggle("hidden"); }
    else if (mod && e.shiftKey && e.key.toLowerCase() === "m") { e.preventDefault(); showPanel("problems"); }
    else if (mod && e.key.toLowerCase() === "s") {
      const ed = SuicjEditor.currentEd();
      if (ed && document.activeElement === ed.taEl) return; // 编辑器自己处理
      e.preventDefault(); if (ed) ed.save();
    }
  });
  $("#quick-input input").addEventListener("input", (e) => qiUpdate(e.target.value));
  $("#quick-input input").addEventListener("keydown", (e) => {
    if (e.key === "Escape") closeQuickInput();
    else if (e.key === "ArrowDown" || e.key === "ArrowUp") {
      e.preventDefault();
      const items = $$("#quick-input .qi-item[data-idx]");
      items.forEach(i => i.classList.remove("focused"));
      qiSel = (qiSel + (e.key === "ArrowDown" ? 1 : -1) + qiItems.length) % qiItems.length;
      items[qiSel] && items[qiSel].classList.add("focused");
    } else if (e.key === "Enter") qiRun(qiSel);
  });
  $("#quick-input").addEventListener("mousedown", (e) => { if (e.target.id === "quick-input") closeQuickInput(); });

  // 菜单栏（演示级：点击菜单提示对应命令面板能力）
  $$(".menu-item").forEach(m => m.onclick = () => {
    const t = m.textContent;
    if (t === "文件") { toast("info", "文件", "使用 Ctrl+P 快速打开文件，Ctrl+S 保存"); }
    if (t === "查看") { toast("info", "查看", "Ctrl+B 侧边栏 / Ctrl+` 面板 / Ctrl+Shift+M 问题"); }
    if (t === "终端") { showPanel("terminal"); }
    if (t === "帮助") { toast("info", "suicoco v0.1.0", "插件驱动 · .suicj 自有插件格式 · 官方源环境安装"); }
  });

  // 插件引擎订阅：贡献变化时刷新编辑器着色与视图
  PluginEngine.onChange(() => { SuicjEditor.refreshAll(); if (activeView === "plugins" || activeView === "run") renderSidebar(); });

  // 初始视图
  showActivity("explorer");
  renderTabs();
  refreshStatusbar();
  showPanel("terminal");
  termLog("suicoco 终端（演示模式）— 输入 help 查看命令；桌面版将运行于 Ubuntu 精简系统环境之上。", "dim");
  termLog("提示: 先在「插件市场」安装语言插件，再在「系统与环境安装」安装 Ubuntu 底座与语言环境。", "dim");
  toast("info", "欢迎使用 suicoco", "类 VSCode 编辑器 · 插件驱动 IDE · Ctrl+Shift+P 打开命令面板");
}
document.addEventListener("DOMContentLoaded", boot);
})();

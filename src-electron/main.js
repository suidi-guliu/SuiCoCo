/* ============================================================
   suicoco — Electron 主进程（桌面壳骨架）
   职责（阶段5）：
   1. 创建窗口，加载 app/ 渲染层（VSCode 风格 UI）
   2. 通过 IPC 执行 scripts/ 下的安装脚本（Ubuntu 精简系统 /
      语言环境，全部走官方源下载），并把 stdout 逐行回传给
      图形化安装向导渲染进度
   3. .suicj 第三方插件导入与官方插件仓库下载（GitHub 链接
      在设置中配置），落地到 ~/.suicoco/plugins/
   ============================================================ */
const { app, BrowserWindow, ipcMain, dialog } = require("electron");
const path = require("path");
const fs = require("fs");
const { spawn } = require("child_process");

const SUICOCO_HOME = path.join(app.getPath("home"), ".suicoco");
const PLUGINS_DIR = path.join(SUICOCO_HOME, "plugins");
const ENV_MARKER = path.join(SUICOCO_HOME, "envs", ".installed");

let win = null;

function createWindow() {
  win = new BrowserWindow({
    width: 1280,
    height: 800,
    title: "suicoco",
    backgroundColor: "#1f1f1f",
    autoHideMenuBar: false,
    webPreferences: {
      preload: path.join(__dirname, "preload.js"),
      contextIsolation: true,
      nodeIntegration: false
    }
  });
  win.loadFile(path.join(__dirname, "..", "app", "index.html"));
}

/* ---------- 工具：逐行执行脚本并回传进度 ---------- */
function runScript(winSender, cmd, args) {
  return new Promise((resolve) => {
    const proc = spawn(cmd, args, { env: { ...process.env, SUICOCO_HOME } });
    let lastLine = "";
    proc.stdout.on("data", (d) => {
      d.toString().split("\n").filter(Boolean).forEach((line) => {
        lastLine = line;
        winSender.send("installer:log", line);
      });
    });
    proc.stderr.on("data", (d) => winSender.send("installer:log", d.toString()));
    proc.on("exit", (code) => resolve({ code, lastLine }));
  });
}

/* ---------- IPC：安装器（Ubuntu 精简系统 / 语言环境） ---------- */
ipcMain.handle("env:install", async (e, target) => {
  const scripts = path.join(__dirname, "..", "scripts");
  if (target === "ubuntu") {
    return runScript(e.sender, "bash", [path.join(scripts, "install-ubuntu-minimal.sh")]);
  }
  if (["cpp", "python", "java"].includes(target)) {
    return runScript(e.sender, "bash", [path.join(scripts, "install-lang-env.sh"), target]);
  }
  return { code: 1, error: "未知安装目标: " + target };
});

ipcMain.handle("env:list", () => {
  try { return fs.readdirSync(ENV_MARKER); } catch (_) { return []; }
});

/* ---------- IPC：官方插件仓库（GitHub 链接，设置中配置） ---------- */
ipcMain.handle("plugins:downloadOfficial", async (e, repoUrl, pluginId) => {
  // 从 GitHub 仓库下载 .suicj（raw 链接），落地后通知渲染层安装
  const url = `${repoUrl.replace(/\/$/, "")}/raw/main/release/${pluginId}.suicj`;
  const out = path.join(PLUGINS_DIR, pluginId + ".suicj");
  const res = await fetch(url);
  if (!res.ok) return { error: "下载失败: HTTP " + res.status };
  fs.mkdirSync(PLUGINS_DIR, { recursive: true });
  fs.writeFileSync(out, Buffer.from(await res.arrayBuffer()));
  return { file: out };
});

/* ---------- IPC：第三方插件导入（仅 .suicj） ---------- */
ipcMain.handle("plugins:importThirdParty", async (e) => {
  const r = await dialog.showOpenDialog(win, {
    title: "导入第三方插件（仅支持 .suicj 自有格式）",
    filters: [{ name: "suicoco 插件", extensions: ["suicj"] }],
    properties: ["openFile"]
  });
  if (r.canceled || !r.filePaths.length) return null;
  const src = r.filePaths[0];
  const dest = path.join(PLUGINS_DIR, path.basename(src));
  fs.mkdirSync(PLUGINS_DIR, { recursive: true });
  fs.copyFileSync(src, dest);
  return { file: dest };
});

app.whenReady().then(createWindow);
app.on("window-all-closed", () => { if (process.platform !== "darwin") app.quit(); });
app.on("activate", () => { if (BrowserWindow.getAllWindows().length === 0) createWindow(); });

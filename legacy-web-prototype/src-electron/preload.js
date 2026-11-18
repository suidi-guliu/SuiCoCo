/* ============================================================
   suicoco — 预加载脚本（渲染层与主进程之间的安全桥）
   暴露 suicocoNative：安装器进度 / 官方插件下载 / 第三方导入
   ============================================================ */
const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("suicocoNative", {
  // 安装 Ubuntu 精简系统或语言环境（官方源），返回退出码
  installEnv: (target) => ipcRenderer.invoke("env:install", target),
  listEnvs: () => ipcRenderer.invoke("env:list"),
  // 安装器日志逐行回传（用于图形向导的实时日志区）
  onInstallerLog: (cb) => ipcRenderer.on("installer:log", (_e, line) => cb(line)),
  // 从 GitHub 官方仓库下载插件
  downloadOfficial: (repoUrl, pluginId) =>
    ipcRenderer.invoke("plugins:downloadOfficial", repoUrl, pluginId),
  // 第三方插件导入（仅 .suicj）
  importThirdParty: () => ipcRenderer.invoke("plugins:importThirdParty")
});

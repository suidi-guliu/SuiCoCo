/* ============================================================
   suicoco — 插件引擎 (.suicj Plugin Engine)
   自有插件格式：ZIP 容器 + manifest.json + main.js
   生命周期：安装(install) → 等待激活事件 → activate(ctx) → 贡献注册
   不兼容 VSCode/其他插件格式。
   ============================================================ */
const PluginEngine = (() => {

  const SUICOCO_VERSION = "0.1.0";
  const state = {
    plugins: [],            // { manifest, main, status: 'installed'|'activated', source: 'official'|'third' }
    languages: [],          // 注册的语言 { id, name, extensions, grammar, pluginId }
    completions: [],        // { languageId, provide(prefix) -> [{label, detail, insert}] }
    commands: [],           // { id, label, run }
    tasks: [],              // { id, label, language, command, pluginId }
    snippets: [],
    problems: [],           // { file, line, severity, message }
    hooks: { onSave: [] }
  };

  /* ---------- 版本兼容（semver 简化版） ---------- */
  function engineSatisfies(range) {
    const m = /^>=\s*(\d+)\.(\d+)\.(\d+)$/.exec((range || "").trim());
    if (!m) return true; // 缺省视为兼容
    const req = m.slice(1).map(Number);
    const cur = SUICOCO_VERSION.split(".").map(Number);
    for (let i = 0; i < 3; i++) if (cur[i] > req[i]) return true;
    return cur[0] === req[0] && cur[1] >= req[1];
  }

  /* ---------- manifest 校验（第三方导入的第一道闸） ---------- */
  function validateManifest(manifest) {
    const errs = [];
    const need = ["id", "name", "version", "main", "engines"];
    for (const k of need) if (!manifest[k]) errs.push("manifest.json 缺少必填字段: " + k);
    if (manifest.id && !/^[a-z0-9][a-z0-9.-]*$/i.test(manifest.id)) errs.push("插件 id 含非法字符");
    if (manifest.main && !/\.js$/.test(manifest.main)) errs.push("入口 main 必须为 .js 文件");
    if (manifest.engines && !engineSatisfies(manifest.engines.suicoco)) errs.push("插件要求 suicoco " + JSON.stringify(manifest.engines));
    return errs;
  }

  /* ---------- 安装（来自内嵌官方索引或第三方 .suicj 导入） ---------- */
  function installFromSource(source, origin) {
    const manifest = source.manifest;
    const errs = validateManifest(manifest);
    if (errs.length) throw new Error(errs.join("; "));
    const mainCode = source.files[manifest.main];
    if (mainCode == null) throw new Error("插件包缺少入口文件 " + manifest.main);
    if (state.plugins.some(p => p.manifest.id === manifest.id)) {
      uninstall(manifest.id); // 重装即更新
    }
    const plugin = { manifest, mainCode, status: "installed", source: origin };
    state.plugins.push(plugin);
    emitChange();
    maybeActivate(plugin);
    return plugin;
  }

  /** 从 .suicj 二进制导入（第三方插件入口） */
  async function importSuicj(arrayBuffer) {
    const files = await SuicjZip.unzip(arrayBuffer);
    if (!files["manifest.json"]) throw new Error("无效的 .suicj 包：缺少 manifest.json");
    let manifest;
    try { manifest = JSON.parse(files["manifest.json"]); }
    catch (e) { throw new Error("manifest.json 不是合法 JSON"); }
    return installFromSource({ manifest, files }, "third");
  }

  /* ---------- 激活 ---------- */
  function activationEvents(plugin) {
    const evs = plugin.manifest.activationEvents || [];
    // 支持 "onStartup" / "onLanguage:<id>"
    return evs;
  }

  function maybeActivate(plugin) {
    const evs = activationEvents(plugin);
    if (!evs.length || evs.includes("onStartup")) activate(plugin);
  }

  function ensureActivatedForLanguage(langId) {
    for (const p of state.plugins) {
      if (p.status !== "installed") continue;
      if ((p.manifest.activationEvents || []).includes("onLanguage:" + langId)) activate(p);
    }
  }

  /** 按文件扩展名懒激活：依据 manifest.contributes.languages 声明 */
  function ensureActivatedForFile(fileName) {
    const ext = "." + fileName.split(".").pop().toLowerCase();
    for (const p of state.plugins) {
      if (p.status !== "installed") continue;
      const langs = (p.manifest.contributes && p.manifest.contributes.languages) || [];
      for (const l of langs) {
        if ((l.extensions || []).includes(ext) &&
            (p.manifest.activationEvents || []).includes("onLanguage:" + l.id)) {
          activate(p);
        }
      }
    }
  }

  function activate(plugin) {
    if (plugin.status !== "installed") return; // installed → activating → activated，杜绝重入递归
    plugin.status = "activating";
    const ctx = makeContext(plugin);
    const wrapped =
      "const module={exports:{}};const exports=module.exports;" +
      "(function(require, module, exports, ctx){\n" + plugin.mainCode + "\n})({}, module, exports, ctx);" +
      "module.exports.activate ? module.exports.activate(ctx) : null;";
    const run = new Function("ctx", wrapped);
    run(ctx);
    plugin.status = "activated";
    emitChange();
  }

  function uninstall(id) {
    const idx = state.plugins.findIndex(p => p.manifest.id === id);
    if (idx === -1) return;
    const pid = state.plugins[idx].manifest.id;
    state.plugins.splice(idx, 1);
    // 清理该插件注册的贡献点
    state.languages = state.languages.filter(l => l.pluginId !== pid);
    state.completions = state.completions.filter(c => c.pluginId !== pid);
    state.commands = state.commands.filter(c => c.pluginId !== pid);
    state.tasks = state.tasks.filter(t => t.pluginId !== pid);
    emitChange();
  }

  /* ---------- 插件 API：activate(ctx) 中可用的全部能力 ---------- */
  function makeContext(plugin) {
    const pid = plugin.manifest.id;
    const api = {
      id: pid,
      version: plugin.manifest.version,
      app: { name: "suicoco", version: SUICOCO_VERSION },

      languages: {
        registerLanguage(lang) {
          state.languages.push(Object.assign({ pluginId: pid }, lang));
          emitChange();
        },
        registerCompletionItemProvider(languageId, provider) {
          state.completions.push(Object.assign({ languageId, pluginId: pid }, provider));
        }
      },

      commands: {
        registerCommand(id, label, run) {
          state.commands.push({ id, label, run, pluginId: pid });
        }
      },

      tasks: {
        registerTask(t) { state.tasks.push(Object.assign({ pluginId: pid }, t)); },
        registerTasks(arr) { arr.forEach(t => state.tasks.push(Object.assign({ pluginId: pid }, t))); }
      },

      workspace: {
        onDidSave(cb) { state.hooks.onSave.push(cb); },
        getOpenDocument(file) { return window.suicocoHost && window.suicocoHost.getDocument(file); }
      },

      window: {
        showInformationMessage(msg) { window.suicocoHost && window.suicocoHost.notify("info", plugin.manifest.name, msg); },
        showWarningMessage(msg) { window.suicocoHost && window.suicocoHost.notify("warn", plugin.manifest.name, msg); },
        setStatusBarMessage(msg, ms) { window.suicocoHost && window.suicocoHost.statusFlash(msg, ms || 4000); }
      },

      // 语言环境查询/请求安装（插件与安装向导联动的桥）
      env: {
        isInstalled(envId) { return window.suicocoHost && window.suicocoHost.envIsInstalled(envId); },
        requestInstall(envId) { window.suicocoHost && window.suicocoHost.requestEnvInstall(envId); }
      },

      diagnostics: {
        setProblems(list) {
          state.problems = state.problems.filter(p => p.pluginId !== pid)
            .concat(list.map(p => Object.assign({ pluginId: pid }, p)));
          emitChange();
        }
      }
    };
    return api;
  }

  function fireSaveHooks(file, content) {
    state.hooks.onSave.forEach(cb => { try { cb(file, content); } catch (e) { console.warn(e); } });
  }

  /* ---------- 订阅 ---------- */
  const listeners = [];
  function onChange(cb) { listeners.push(cb); }
  function emitChange() { listeners.forEach(cb => { try { cb(); } catch (e) {} }); }

  /* ---------- 查询 ---------- */
  function getLanguage(fileName) {
    ensureActivatedForFile(fileName); // 先按扩展名懒激活匹配的语言插件
    const ext = "." + fileName.split(".").pop().toLowerCase();
    // 语言列表倒序匹配以允许 cpp 覆盖 c 的 .h
    const langs = state.languages.filter(l => (l.extensions || []).includes(ext));
    return langs.length ? langs[langs.length - 1] : null;
  }
  function getCompletions(languageId, prefix) {
    let items = [];
    for (const c of state.completions) {
      if (c.languageId !== languageId) continue;
      try { items = items.concat(c.provideCompletionItems(prefix) || []); } catch (e) {}
    }
    return items;
  }
  function getTasksFor(languageId) { return state.tasks.filter(t => !languageId || t.language === languageId); }
  function getCommands() { return state.commands.slice(); }
  function getPlugins() { return state.plugins.slice(); }
  function getProblems() { return state.problems.slice(); }

  return {
    SUICOCO_VERSION, state,
    installFromSource, importSuicj, uninstall,
    ensureActivatedForLanguage, ensureActivatedForFile, activate,
    onChange, fireSaveHooks,
    getLanguage, getCompletions, getTasksFor, getCommands,
    getPlugins, getProblems
  };
})();

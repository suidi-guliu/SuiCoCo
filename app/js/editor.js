/* ============================================================
   suicoco — 编辑器内核
   语法着色完全由插件注册的 grammar 驱动——插件未安装时
   就是纯文本编辑器（体现"自有插件实现完整 IDE 功能"）。
   ============================================================ */
const SuicjEditor = (() => {

  const LINE_H = 19;          // 行高（对齐 VSCode 14px/19px）
  const wraps = [];           // 打开的编辑器实例（多标签）
  let host = null;            // #editor-host
  let current = null;         // 当前活动编辑器
  let onChangeCb = null, onSaveCb = null;

  /* ---------------- 通用分词器（grammar 驱动） ---------------- */
  function tokenizeLine(line, grammar, ctxState) {
    const tokens = [];
    const push = (text, cls) => { if (text) tokens.push({ text, cls }); };
    let pos = 0, inBlock = ctxState.inBlock;

    const esc = s => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    const wordAt = (re) => { re.lastIndex = pos; const m = re.exec(line); return m && m.index === pos ? m : null; };

    while (pos < line.length) {
      if (inBlock) {
        const end = line.indexOf(grammar.blockComment[1], pos);
        if (end === -1) { push(line.slice(pos), "tok-comment"); pos = line.length; }
        else { push(line.slice(pos, end + grammar.blockComment[1].length), "tok-comment"); pos = end + grammar.blockComment[1].length; inBlock = false; }
        continue;
      }
      const rest = line.slice(pos);
      // 行注释
      if (grammar.lineComment && rest.startsWith(grammar.lineComment)) { push(rest, "tok-comment"); pos = line.length; continue; }
      // 块注释开始
      if (grammar.blockComment && rest.startsWith(grammar.blockComment[0])) {
        const end = line.indexOf(grammar.blockComment[1], pos + grammar.blockComment[0].length);
        if (end === -1) { push(rest, "tok-comment"); pos = line.length; inBlock = true; }
        else { push(line.slice(pos, end + 2), "tok-comment"); pos = end + 2; }
        continue;
      }
      // 字符串
      let matched = false;
      for (const q of grammar.stringQuotes || ['"', "'"]) {
        if (rest[0] === q) {
          let i = 1;
          while (i < rest.length && rest[i] !== q) { if (rest[i] === "\\") i++; i++; }
          push(rest.slice(0, Math.min(i + 1, rest.length)), "tok-string");
          pos += Math.min(i + 1, rest.length); matched = true; break;
        }
      }
      if (matched) continue;
      // 装饰器（Python @）
      if (grammar.decorator) {
        const m = wordAt(new RegExp(grammar.decorator, "y"));
        if (m) { push(m[0], "tok-decorator"); pos += m[0].length; continue; }
      }
      // 预处理（C/C++ #）
      if (grammar.preprocessor && rest[0] === "#") {
        const m = wordAt(/^\s*#\s*\w+/y);
        if (m) { push(m[0], "tok-preproc"); pos += m[0].length; continue; }
      }
      // 数字
      const num = wordAt(/\d[\d.xXaAbBcCdDeEfF_.]*/y);
      if (num) { push(num[0], "tok-number"); pos += num[0].length; continue; }
      // 单词
      const word = wordAt(/[A-Za-z_][A-Za-z0-9_]*/y);
      if (word) {
        const w = word[0];
        let cls = "tok-ident";
        if (grammar.control && grammar.control.includes(w)) cls = "tok-control";
        else if (grammar.keywords && grammar.keywords.includes(w)) cls = "tok-keyword";
        else if (grammar.types && grammar.types.includes(w)) cls = "tok-type";
        else if (grammar.constants && grammar.constants.includes(w)) cls = "tok-number";
        else if (grammar.classes && grammar.classes.includes(w)) cls = "tok-class";
        else if (grammar.builtins && grammar.builtins.includes(w)) cls = "tok-var";
        else {
          // 函数调用：word 后紧跟 (
          const after = line.slice(pos + w.length);
          if (/^\s*\(/.test(after)) cls = "tok-func";
          // 大写开头视为类型
          else if (/^[A-Z]/.test(w) && w.length > 1) cls = "tok-class";
          else if (/^_?[a-z][a-zA-Z0-9]*_?$/.test(w) && grammar.snakeIsVar) cls = "tok-var";
        }
        push(w, cls); pos += w.length; continue;
      }
      // 普通字符
      const ch = rest[0];
      push(ch, /[{}()[\];,.]/.test(ch) ? "tok-ident" : "tok-operator");
      pos += 1;
    }
    ctxState.inBlock = inBlock;
    return tokens;
  }

  function escapeHtml(s) {
    return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  }

  function renderTokens(tokens) {
    return tokens.map(t => t.cls ? `<span class="${t.cls}">${escapeHtml(t.text)}</span>` : escapeHtml(t.text)).join("");
  }

  /* ---------------- 编辑器实例 ---------------- */
  class EditorTab {
    constructor(file, content) {
      this.file = file;
      this.content = content;
      this.dirty = false;
      this.grammar = null;
      this.langId = null;
      this.cursor = { ln: 1, col: 1 };
      this.buildDom();
    }
    buildDom() {
      const wrap = document.createElement("div");
      wrap.className = "editor-wrap";
      wrap.innerHTML = `
        <div class="gutter" id="gutter"></div>
        <div id="code-area">
          <pre id="highlight-layer"></pre>
          <textarea id="textarea-input" spellcheck="false" autocomplete="off"></textarea>
        </div>`;
      this.dom = wrap;
      this.gutterEl = wrap.querySelector(".gutter");
      this.hlEl = wrap.querySelector("#highlight-layer");
      this.taEl = wrap.querySelector("#textarea-input");
      this.taEl.value = this.content;

      this.taEl.addEventListener("input", () => {
        this.content = this.taEl.value;
        this.setDirty(true);
        this.refresh();
        if (onChangeCb) onChangeCb(this);
        app.suggestOnType(this); // 补全触发（app.js 提供）
      });
      this.taEl.addEventListener("keydown", (e) => {
        if (e.key === "Tab") {
          e.preventDefault();
          const s = this.taEl.selectionStart, t = this.taEl.selectionEnd;
          this.taEl.value = this.taEl.value.slice(0, s) + "    " + this.taEl.value.slice(t);
          this.taEl.selectionStart = this.taEl.selectionEnd = s + 4;
          this.content = this.taEl.value; this.setDirty(true); this.refresh();
          return;
        }
        if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "s") { e.preventDefault(); this.save(); return; }
        app.editorKeydown(e, this);
      });
      ["keyup", "click", "focus"].forEach(evt =>
        this.taEl.addEventListener(evt, () => { this.updateCursor(); app.updateStatusCursor(this.cursor); }));
      this.taEl.addEventListener("scroll", () => {
        // 高亮层与输入层同步滚动（隐藏滚动条，靠 textarea 自身滚动）
        this.hlEl.scrollTop = this.taEl.scrollTop;
        this.hlEl.scrollLeft = this.taEl.scrollLeft;
        this.gutterEl.scrollTop = this.taEl.scrollTop;
      });
    }
    attachGrammar() {
      const lang = PluginEngine.getLanguage(this.file);
      this.grammar = lang ? lang.grammar : null;
      this.langName = lang ? lang.name : "纯文本";
      this.langId = lang ? lang.id : null;
      if (lang) PluginEngine.ensureActivatedForLanguage(lang.id);
    }
    setDirty(d) {
      this.dirty = d;
      if (onChangeCb) onChangeCb(this);
    }
    save() {
      this.content = this.taEl.value;
      this.setDirty(false);
      this.refresh();
      PluginEngine.fireSaveHooks(this.file, this.content);
      if (onSaveCb) onSaveCb(this);
      window.suicocoHost && window.suicocoHost.notify("info", "已保存", this.file);
    }
    getContent() { return this.taEl.value; }
    updateCursor() {
      const upto = this.taEl.value.slice(0, this.taEl.selectionStart);
      const lines = upto.split("\n");
      this.cursor = { ln: lines.length, col: lines[lines.length - 1].length + 1 };
    }
    insertAtCursor(text) {
      const s = this.taEl.selectionStart, e = this.taEl.selectionEnd;
      this.taEl.value = this.taEl.value.slice(0, s) + text + this.taEl.value.slice(e);
      this.taEl.selectionStart = this.taEl.selectionEnd = s + text.length;
      this.content = this.taEl.value;
      this.setDirty(true);
      this.refresh();
      if (onChangeCb) onChangeCb(this);
    }
    refresh() {
      this.attachGrammar();
      const lines = this.content.split("\n");
      const ctxState = { inBlock: false };
      let html = "";
      const g = this.grammar;
      for (const line of lines) {
        if (!g) { html += escapeHtml(line) + "\n"; continue; }
        html += renderTokens(tokenizeLine(line, g, ctxState)) + "\n";
      }
      this.hlEl.innerHTML = html;
      this.gutterEl.innerHTML = lines.map((_, i) => `<div>${i + 1}</div>`).join("");
      // 活动行高亮由 CSS 无法实现于 pre，这里在行首加左侧标记（简化的当前行指示）
    }
  }

  /* ---------------- 多标签管理 ---------------- */
  function init(hostEl, cb) {
    host = hostEl;
    onChangeCb = cb.onEditorChange;
    onSaveCb = cb.onSave;
  }
  function open(file, content) {
    let ed = wraps.find(e => e.file === file);
    if (!ed) { ed = new EditorTab(file, content); wraps.push(ed); }
    current = ed; render(); return ed;
  }
  function close(file) {
    const idx = wraps.findIndex(e => e.file === file);
    if (idx !== -1) {
      if (wraps[idx].dirty && !confirm("「" + file + "」有未保存的更改，仍要关闭？")) return false;
      wraps.splice(idx, 1);
      if (current && current.file === file) current = wraps[Math.min(idx, wraps.length - 1)] || null;
      render();
    }
    return true;
  }
  function activate(file) {
    current = wraps.find(e => e.file === file) || current;
    render();
  }
  function currentEd() { return current; }
  function render() {
    if (!host) return;
    host.innerHTML = "";
    if (!current) {
      const empty = document.createElement("div");
      empty.className = "editor-empty";
      empty.innerHTML = `<div style="text-align:center">
        <div style="font-size:60px;line-height:1">‹/›</div>
        <div style="margin-top:14px;font-size:17px;color:#8b8b8b;font-weight:300">suicoco</div>
        <div style="margin-top:6px;font-size:13px;color:#6e6e6e">类 VSCode 编辑器 · 插件驱动 · .suicj 插件生态</div>
        <div class="keys" style="text-align:center">
          <div><kbd>Ctrl</kbd>+<kbd>P</kbd> 快速打开 &nbsp;&nbsp; <kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>P</kbd> 命令面板</div>
          <div style="margin-top:8px"><kbd>Ctrl</kbd>+<kbd>S</kbd> 保存 &nbsp;&nbsp; <kbd>Ctrl</kbd>+<kbd>&#96;</kbd> 终端</div>
        </div></div>`;
      host.appendChild(empty);
      return;
    }
    current.refresh();
    host.appendChild(current.dom);
    current.taEl.focus();
  }
  function refreshAll() { wraps.forEach(e => e.refresh()); render(); }
  function openFiles() { return wraps.map(e => e.file); }
  function find(file) { return wraps.find(e => e.file === file); }

  return { init, open, close, activate, currentEd, refreshAll, openFiles, find, tokenizeLine };
})();

/* suicj.lang.python — Python 语言支持插件 */
"use strict";

const PY_GRAMMAR = {
  lineComment: "#",
  blockComment: null,
  stringQuotes: ['"', "'"],
  decorator: "@[A-Za-z_][A-Za-z0-9_.]*",
  keywords: ["def", "class", "lambda", "import", "from", "as", "global", "nonlocal", "pass",
    "with", "assert", "del", "in", "is", "not", "and", "or", "yield", "async", "await"],
  control: ["if", "elif", "else", "for", "while", "break", "continue", "return", "try", "except", "finally", "raise"],
  builtins: ["print", "len", "range", "int", "str", "float", "bool", "list", "dict", "set", "tuple",
    "open", "enumerate", "zip", "map", "filter", "sorted", "sum", "min", "max", "abs", "type", "isinstance"],
  types: ["self", "cls", "object", "Any", "List", "Dict", "Optional", "Union"],
  constants: ["True", "False", "None"],
  snakeIsVar: true
};

const PY_SNIPPETS = [
  { label: "def", detail: "def name(): …", insert: "def name():\n    ", kind: "snippet" },
  { label: "main", detail: "if __name__ == …", insert: 'if __name__ == "__main__":\n    main()', kind: "snippet" },
  { label: "class", detail: "class Name: …", insert: "class Name:\n    def __init__(self):\n        pass", kind: "snippet" },
  { label: "for", detail: "for i in range(…)", insert: "for i in range(10):\n    ", kind: "snippet" },
  { label: "print", detail: "print(f\"…\")", insert: "print(f\"\")", kind: "snippet" }
];

function activate(ctx) {
  ctx.languages.registerLanguage({
    id: "python", name: "Python", extensions: [".py", ".pyw"], grammar: PY_GRAMMAR
  });

  const items = [].concat(
    PY_GRAMMAR.builtins.map(b => ({ label: b, detail: "builtin", kind: "keyword" })),
    PY_GRAMMAR.control.map(b => ({ label: b, detail: "keyword", kind: "keyword" })),
    PY_GRAMMAR.keywords.map(b => ({ label: b, detail: "keyword", kind: "keyword" })),
    PY_SNIPPETS
  );
  ctx.languages.registerCompletionItemProvider("python", {
    provideCompletionItems: (prefix) => items
  });

  ctx.tasks.registerTasks([
    { id: "suicj.task.python.run", label: "Python: 运行当前文件", language: "python",
      command: 'python3 "${file}"' },
    { id: "suicj.task.python.venv", label: "Python: 创建 venv 并安装 requirements", language: "python",
      command: 'python3 -m venv .venv && .venv/bin/pip install -r requirements.txt' }
  ]);

  ctx.commands.registerCommand("suicj.python.about", "Python: 关于本插件", () => {
    ctx.window.showInformationMessage("Python 语言支持 v" + ctx.version + " · 需要 python 语言环境（官方源安装）。");
  });

  ctx.workspace.onDidSave((file, content) => {
    const probs = [];
    if (/\r\n/.test(content)) probs.push({ file, severity: "info", message: "检测到 CRLF，Python 建议使用 LF 行尾" });
    if (/^\s* *\t/m.test(content)) probs.push({ file, severity: "warning", message: "检测到 Tab 缩进与空格混用（PEP 8 建议统一 4 空格）" });
    ctx.diagnostics.setProblems(probs);
  });

  ctx.window.setStatusBarMessage("Python 已就绪");
}

module.exports = { activate };

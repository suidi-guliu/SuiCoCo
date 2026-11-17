/* suicj.lang.c-cpp — C/C++ 语言支持插件
 * 自有插件格式：manifest.json + main.js，导出 activate(ctx)
 */
"use strict";

const C_GRAMMAR = {
  lineComment: "//",
  blockComment: ["/*", "*/"],
  stringQuotes: ['"', "'"],
  preprocessor: true,
  keywords: ["auto", "bool", "char", "const", "constexpr", "double", "enum", "explicit", "extern",
    "float", "friend", "inline", "int", "long", "mutable", "namespace", "operator", "private",
    "protected", "public", "register", "short", "signed", "sizeof", "static", "struct", "template",
    "typedef", "typename", "union", "unsigned", "using", "virtual", "void", "volatile", "class", "delete", "new", "this"],
  control: ["if", "else", "for", "while", "do", "switch", "case", "default", "break", "continue", "return", "goto", "try", "catch", "throw", "noexcept"],
  types: ["std", "vector", "string", "map", "set", "pair", "array", "cout", "cin", "endl", "size_t", "int8_t", "uint32_t", "FILE"],
  constants: ["true", "false", "nullptr", "NULL"]
};

const CPP_SNIPPETS = [
  { label: "main", detail: "int main() { … }", insert: "int main(int argc, char** argv) {\n    \n    return 0;\n}", kind: "snippet" },
  { label: "for", detail: "for (int i = 0; …)", insert: "for (int i = 0; i < n; i++) {\n    \n}", kind: "snippet" },
  { label: "while", detail: "while (…) { … }", insert: "while () {\n    \n}", kind: "snippet" },
  { label: "inc", detail: "#include <…>", insert: "#include <stdio.h>", kind: "snippet" },
  { label: "printf", detail: "printf(\"…\\n\", …)", insert: "printf(\"%d\\n\", );", kind: "snippet" },
  { label: "class", detail: "class Name { };", insert: "class Name {\npublic:\n    Name() {}\nprivate:\n};", kind: "snippet" }
];

function activate(ctx) {
  const grammar = C_GRAMMAR;

  ctx.languages.registerLanguage({
    id: "c", name: "C", extensions: [".c", ".h"], grammar: grammar
  });
  ctx.languages.registerLanguage({
    id: "cpp", name: "C++", extensions: [".cpp", ".cc", ".cxx", ".hpp", ".ipp"], grammar: grammar
  });

  const keywordItems = [].concat(
    grammar.keywords.map(k => ({ label: k, detail: "keyword", kind: "keyword" })),
    grammar.control.map(k => ({ label: k, detail: "control", kind: "keyword" })),
    grammar.types.map(k => ({ label: k, detail: "type", kind: "keyword" }))
  );
  ctx.languages.registerCompletionItemProvider("c", {
    provideCompletionItems: (prefix) => keywordItems.concat(CPP_SNIPPETS)
  });
  ctx.languages.registerCompletionItemProvider("cpp", {
    provideCompletionItems: (prefix) => keywordItems.concat(CPP_SNIPPETS)
  });

  ctx.tasks.registerTasks([
    { id: "suicj.task.c.run", label: "C: 编译并运行当前文件", language: "c",
      command: 'gcc "${file}" -o "${fileBase}.out && ./${fileBase}.out"' },
    { id: "suicj.task.cpp.run", label: "C++: 编译并运行当前文件", language: "cpp",
      command: 'g++ -std=c++17 "${file}" -o "${fileBase}.out && ./${fileBase}.out"' },
    { id: "suicj.task.cpp.debug", label: "C++: 调试当前文件 (gdb)", language: "cpp",
      command: 'g++ -g "${file}" -o "${fileBase}.dbg && gdb ${fileBase}.dbg"' }
  ]);

  ctx.commands.registerCommand("suicj.c-cpp.about", "C/C++: 关于本插件", () => {
    ctx.window.showInformationMessage("C/C++ 语言支持 v" + ctx.version + " · 运行任务需要 cpp 语言环境（官方源安装）。");
  });

  // 简单括号配对诊断（保存时触发）
  ctx.workspace.onDidSave((file, content) => {
    const probs = [];
    const pairs = [["(", ")"], ["{", "}"]];
    for (const [a, b] of pairs) {
      const ca = (content.match(new RegExp("\\" + a, "g")) || []).length;
      const cb = (content.match(new RegExp("\\" + b, "g")) || []).length;
      if (ca !== cb) probs.push({ file, severity: "warning", message: `括号不配对: '${a}' ${ca} 个 vs '${b}' ${cb} 个` });
    }
    if (/\t/.test(content)) probs.push({ file, severity: "info", message: "建议使用 4 空格缩进代替 Tab（suicoco 默认 Tab 展宽 4）" });
    ctx.diagnostics.setProblems(probs);
  });

  ctx.window.setStatusBarMessage("C/C++ 已就绪");
}

module.exports = { activate };

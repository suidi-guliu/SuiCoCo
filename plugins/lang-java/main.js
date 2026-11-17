/* suicj.lang.java — Java 语言支持插件 */
"use strict";

const JAVA_GRAMMAR = {
  lineComment: "//",
  blockComment: ["/*", "*/"],
  stringQuotes: ['"', "'"],
  keywords: ["abstract", "boolean", "byte", "class", "const", "double", "enum", "extends", "final",
    "float", "implements", "import", "instanceof", "int", "interface", "long", "native", "package",
    "private", "protected", "public", "short", "static", "strictfp", "super", "synchronized", "this",
    "throw", "throws", "transient", "volatile", "void", "var", "record", "sealed", "permits"],
  control: ["if", "else", "for", "while", "do", "switch", "case", "default", "break", "continue", "return", "try", "catch", "finally", "assert", "new"],
  types: ["String", "Integer", "Double", "Float", "Boolean", "Character", "Object", "System", "Math", "List", "Map", "ArrayList", "HashMap", "Optional", "Stream"],
  constants: ["true", "false", "null"]
};

const JAVA_SNIPPETS = [
  { label: "main", detail: "public static void main(…)", insert: "public static void main(String[] args) {\n    \n}", kind: "snippet" },
  { label: "sout", detail: "System.out.println(…)", insert: "System.out.println();", kind: "snippet" },
  { label: "class", detail: "public class Name { … }", insert: "public class Name {\n}", kind: "snippet" },
  { label: "for", detail: "for (int i = 0; …)", insert: "for (int i = 0; i < n; i++) {\n    \n}", kind: "snippet" }
];

function activate(ctx) {
  ctx.languages.registerLanguage({
    id: "java", name: "Java", extensions: [".java"], grammar: JAVA_GRAMMAR
  });

  const items = [].concat(
    JAVA_GRAMMAR.keywords.map(k => ({ label: k, detail: "keyword", kind: "keyword" })),
    JAVA_GRAMMAR.control.map(k => ({ label: k, detail: "keyword", kind: "keyword" })),
    JAVA_GRAMMAR.types.map(k => ({ label: k, detail: "type", kind: "keyword" })),
    JAVA_SNIPPETS
  );
  ctx.languages.registerCompletionItemProvider("java", {
    provideCompletionItems: (prefix) => items
  });

  ctx.tasks.registerTasks([
    { id: "suicj.task.java.run", label: "Java: 编译并运行当前文件", language: "java",
      command: 'javac "${file}" && java ${fileBase}' },
    { id: "suicj.task.java.jar", label: "Java: 打包为可执行 jar", language: "java",
      command: 'javac "${file}" && jar cfe ${fileBase}.jar ${fileBase} *.class' }
  ]);

  ctx.commands.registerCommand("suicj.java.about", "Java: 关于本插件", () => {
    ctx.window.showInformationMessage("Java 语言支持 v" + ctx.version + " · 需要 OpenJDK 17 语言环境（官方源安装）。");
  });

  ctx.workspace.onDidSave((file, content) => {
    const probs = [];
    const cls = /class\s+([A-Z]\w*)/.exec(content);
    const base = file.split("/").pop().replace(/\.java$/, "");
    if (cls && cls[1] !== base) {
      probs.push({ file, severity: "warning", message: `public 类名 '${cls[1]}' 与文件名 '${base}.java' 不一致` });
    }
    ctx.diagnostics.setProblems(probs);
  });

  ctx.window.setStatusBarMessage("Java 已就绪");
}

module.exports = { activate };

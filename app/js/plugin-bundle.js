// 本文件由 scripts/embed-plugins.py 自动生成，勿手改
// 官方插件索引（演示版内嵌；桌面版从 GitHub 插件仓库拉取 index.json 与 .suicj）
window.SUICOCO_OFFICIAL_INDEX = [
  {
    "source": "official",
    "publisher": "suicoco-official",
    "description": "第三方插件开发示例：注册命令与状态栏消息，演示 .suicj 插件 API 最小用法。可将其 zip 打包为 .suicj 后在软件内导入测试。",
    "downloads": 12000,
    "rating": 5.0,
    "manifest": {
      "id": "suicj.example.hello",
      "name": "Hello suicoco（示例插件）",
      "version": "0.1.0",
      "publisher": "suicoco-official",
      "description": "第三方插件开发示例：注册命令与状态栏消息，演示 .suicj 插件 API 最小用法。可将其 zip 打包为 .suicj 后在软件内导入测试。",
      "engines": {
        "suicoco": ">=0.1.0"
      },
      "main": "main.js",
      "categories": [
        "示例"
      ],
      "activationEvents": [
        "onStartup"
      ]
    },
    "files": {
      "main.js": "/* suicj.example.hello — 最小示例插件\n * 用途：第三方开发者参考 .suicj 插件的最小结构。\n * 打包：将本目录打包为 zip 后重命名 .suicj（或用 scripts/build-plugin.sh）。\n */\n\"use strict\";\n\nfunction activate(ctx) {\n  ctx.window.showInformationMessage(\"Hello suicoco！我是示例插件「\" + ctx.id + \"」v\" + ctx.version);\n\n  ctx.commands.registerCommand(\"suicj.example.hello.greet\", \"Hello: 打个招呼\", () => {\n    ctx.window.showInformationMessage(\"你好，来自示例插件 👋（软件本体 v\" + ctx.app.version + \"）\");\n  });\n\n  ctx.commands.registerCommand(\"suicj.example.hello.env\", \"Hello: 查询环境安装状态\", () => {\n    const env = [\"ubuntu\", \"cpp\", \"python\", \"java\"].map(e => e + \"=\" + (ctx.env.isInstalled(e) ? \"已安装\" : \"未安装\")).join(\", \");\n    ctx.window.showInformationMessage(\"环境状态: \" + env);\n  });\n\n  ctx.window.setStatusBarMessage(\"Hello 插件已激活\");\n}\n\nmodule.exports = { activate };\n",
      "manifest.json": "{\n  \"id\": \"suicj.example.hello\",\n  \"name\": \"Hello suicoco（示例插件）\",\n  \"version\": \"0.1.0\",\n  \"publisher\": \"suicoco-official\",\n  \"description\": \"第三方插件开发示例：注册命令与状态栏消息，演示 .suicj 插件 API 最小用法。可将其 zip 打包为 .suicj 后在软件内导入测试。\",\n  \"engines\": { \"suicoco\": \">=0.1.0\" },\n  \"main\": \"main.js\",\n  \"categories\": [\"示例\"],\n  \"activationEvents\": [\"onStartup\"]\n}\n"
    }
  },
  {
    "source": "official",
    "publisher": "suicoco-official",
    "description": "C 与 C++ 语法高亮、代码补全、代码片段与编译运行任务（依赖 cpp 语言环境，官方源安装）。",
    "downloads": 486000,
    "rating": 4.8,
    "manifest": {
      "id": "suicj.lang.c-cpp",
      "name": "C/C++ 语言支持",
      "version": "0.1.0",
      "publisher": "suicoco-official",
      "description": "C 与 C++ 语法高亮、代码补全、代码片段与编译运行任务（依赖 cpp 语言环境，官方源安装）。",
      "engines": {
        "suicoco": ">=0.1.0"
      },
      "main": "main.js",
      "requiresEnv": "cpp",
      "categories": [
        "语言环境",
        "编程语言"
      ],
      "activationEvents": [
        "onLanguage:c",
        "onLanguage:cpp"
      ],
      "contributes": {
        "languages": [
          {
            "id": "c",
            "extensions": [
              ".c",
              ".h"
            ]
          },
          {
            "id": "cpp",
            "extensions": [
              ".cpp",
              ".cc",
              ".cxx",
              ".hpp",
              ".ipp"
            ]
          }
        ]
      }
    },
    "files": {
      "main.js": "/* suicj.lang.c-cpp — C/C++ 语言支持插件\n * 自有插件格式：manifest.json + main.js，导出 activate(ctx)\n */\n\"use strict\";\n\nconst C_GRAMMAR = {\n  lineComment: \"//\",\n  blockComment: [\"/*\", \"*/\"],\n  stringQuotes: ['\"', \"'\"],\n  preprocessor: true,\n  keywords: [\"auto\", \"bool\", \"char\", \"const\", \"constexpr\", \"double\", \"enum\", \"explicit\", \"extern\",\n    \"float\", \"friend\", \"inline\", \"int\", \"long\", \"mutable\", \"namespace\", \"operator\", \"private\",\n    \"protected\", \"public\", \"register\", \"short\", \"signed\", \"sizeof\", \"static\", \"struct\", \"template\",\n    \"typedef\", \"typename\", \"union\", \"unsigned\", \"using\", \"virtual\", \"void\", \"volatile\", \"class\", \"delete\", \"new\", \"this\"],\n  control: [\"if\", \"else\", \"for\", \"while\", \"do\", \"switch\", \"case\", \"default\", \"break\", \"continue\", \"return\", \"goto\", \"try\", \"catch\", \"throw\", \"noexcept\"],\n  types: [\"std\", \"vector\", \"string\", \"map\", \"set\", \"pair\", \"array\", \"cout\", \"cin\", \"endl\", \"size_t\", \"int8_t\", \"uint32_t\", \"FILE\"],\n  constants: [\"true\", \"false\", \"nullptr\", \"NULL\"]\n};\n\nconst CPP_SNIPPETS = [\n  { label: \"main\", detail: \"int main() { … }\", insert: \"int main(int argc, char** argv) {\\n    \\n    return 0;\\n}\", kind: \"snippet\" },\n  { label: \"for\", detail: \"for (int i = 0; …)\", insert: \"for (int i = 0; i < n; i++) {\\n    \\n}\", kind: \"snippet\" },\n  { label: \"while\", detail: \"while (…) { … }\", insert: \"while () {\\n    \\n}\", kind: \"snippet\" },\n  { label: \"inc\", detail: \"#include <…>\", insert: \"#include <stdio.h>\", kind: \"snippet\" },\n  { label: \"printf\", detail: \"printf(\\\"…\\\\n\\\", …)\", insert: \"printf(\\\"%d\\\\n\\\", );\", kind: \"snippet\" },\n  { label: \"class\", detail: \"class Name { };\", insert: \"class Name {\\npublic:\\n    Name() {}\\nprivate:\\n};\", kind: \"snippet\" }\n];\n\nfunction activate(ctx) {\n  const grammar = C_GRAMMAR;\n\n  ctx.languages.registerLanguage({\n    id: \"c\", name: \"C\", extensions: [\".c\", \".h\"], grammar: grammar\n  });\n  ctx.languages.registerLanguage({\n    id: \"cpp\", name: \"C++\", extensions: [\".cpp\", \".cc\", \".cxx\", \".hpp\", \".ipp\"], grammar: grammar\n  });\n\n  const keywordItems = [].concat(\n    grammar.keywords.map(k => ({ label: k, detail: \"keyword\", kind: \"keyword\" })),\n    grammar.control.map(k => ({ label: k, detail: \"control\", kind: \"keyword\" })),\n    grammar.types.map(k => ({ label: k, detail: \"type\", kind: \"keyword\" }))\n  );\n  ctx.languages.registerCompletionItemProvider(\"c\", {\n    provideCompletionItems: (prefix) => keywordItems.concat(CPP_SNIPPETS)\n  });\n  ctx.languages.registerCompletionItemProvider(\"cpp\", {\n    provideCompletionItems: (prefix) => keywordItems.concat(CPP_SNIPPETS)\n  });\n\n  ctx.tasks.registerTasks([\n    { id: \"suicj.task.c.run\", label: \"C: 编译并运行当前文件\", language: \"c\",\n      command: 'gcc \"${file}\" -o \"${fileBase}.out && ./${fileBase}.out\"' },\n    { id: \"suicj.task.cpp.run\", label: \"C++: 编译并运行当前文件\", language: \"cpp\",\n      command: 'g++ -std=c++17 \"${file}\" -o \"${fileBase}.out && ./${fileBase}.out\"' },\n    { id: \"suicj.task.cpp.debug\", label: \"C++: 调试当前文件 (gdb)\", language: \"cpp\",\n      command: 'g++ -g \"${file}\" -o \"${fileBase}.dbg && gdb ${fileBase}.dbg\"' }\n  ]);\n\n  ctx.commands.registerCommand(\"suicj.c-cpp.about\", \"C/C++: 关于本插件\", () => {\n    ctx.window.showInformationMessage(\"C/C++ 语言支持 v\" + ctx.version + \" · 运行任务需要 cpp 语言环境（官方源安装）。\");\n  });\n\n  // 简单括号配对诊断（保存时触发）\n  ctx.workspace.onDidSave((file, content) => {\n    const probs = [];\n    const pairs = [[\"(\", \")\"], [\"{\", \"}\"]];\n    for (const [a, b] of pairs) {\n      const ca = (content.match(new RegExp(\"\\\\\" + a, \"g\")) || []).length;\n      const cb = (content.match(new RegExp(\"\\\\\" + b, \"g\")) || []).length;\n      if (ca !== cb) probs.push({ file, severity: \"warning\", message: `括号不配对: '${a}' ${ca} 个 vs '${b}' ${cb} 个` });\n    }\n    if (/\\t/.test(content)) probs.push({ file, severity: \"info\", message: \"建议使用 4 空格缩进代替 Tab（suicoco 默认 Tab 展宽 4）\" });\n    ctx.diagnostics.setProblems(probs);\n  });\n\n  ctx.window.setStatusBarMessage(\"C/C++ 已就绪\");\n}\n\nmodule.exports = { activate };\n",
      "manifest.json": "{\n  \"id\": \"suicj.lang.c-cpp\",\n  \"name\": \"C/C++ 语言支持\",\n  \"version\": \"0.1.0\",\n  \"publisher\": \"suicoco-official\",\n  \"description\": \"C 与 C++ 语法高亮、代码补全、代码片段与编译运行任务（依赖 cpp 语言环境，官方源安装）。\",\n  \"engines\": { \"suicoco\": \">=0.1.0\" },\n  \"main\": \"main.js\",\n  \"requiresEnv\": \"cpp\",\n  \"categories\": [\"语言环境\", \"编程语言\"],\n  \"activationEvents\": [\"onLanguage:c\", \"onLanguage:cpp\"],\n  \"contributes\": {\n    \"languages\": [\n      { \"id\": \"c\", \"extensions\": [\".c\", \".h\"] },\n      { \"id\": \"cpp\", \"extensions\": [\".cpp\", \".cc\", \".cxx\", \".hpp\", \".ipp\"] }\n    ]\n  }\n}\n"
    }
  },
  {
    "source": "official",
    "publisher": "suicoco-official",
    "description": "Java 语法高亮、补全、代码片段与编译运行任务（依赖 java 语言环境 OpenJDK，官方源安装）。",
    "downloads": 355000,
    "rating": 4.6,
    "manifest": {
      "id": "suicj.lang.java",
      "name": "Java 语言支持",
      "version": "0.1.0",
      "publisher": "suicoco-official",
      "description": "Java 语法高亮、补全、代码片段与编译运行任务（依赖 java 语言环境 OpenJDK，官方源安装）。",
      "engines": {
        "suicoco": ">=0.1.0"
      },
      "main": "main.js",
      "requiresEnv": "java",
      "categories": [
        "语言环境",
        "编程语言"
      ],
      "activationEvents": [
        "onLanguage:java"
      ],
      "contributes": {
        "languages": [
          {
            "id": "java",
            "extensions": [
              ".java"
            ]
          }
        ]
      }
    },
    "files": {
      "main.js": "/* suicj.lang.java — Java 语言支持插件 */\n\"use strict\";\n\nconst JAVA_GRAMMAR = {\n  lineComment: \"//\",\n  blockComment: [\"/*\", \"*/\"],\n  stringQuotes: ['\"', \"'\"],\n  keywords: [\"abstract\", \"boolean\", \"byte\", \"class\", \"const\", \"double\", \"enum\", \"extends\", \"final\",\n    \"float\", \"implements\", \"import\", \"instanceof\", \"int\", \"interface\", \"long\", \"native\", \"package\",\n    \"private\", \"protected\", \"public\", \"short\", \"static\", \"strictfp\", \"super\", \"synchronized\", \"this\",\n    \"throw\", \"throws\", \"transient\", \"volatile\", \"void\", \"var\", \"record\", \"sealed\", \"permits\"],\n  control: [\"if\", \"else\", \"for\", \"while\", \"do\", \"switch\", \"case\", \"default\", \"break\", \"continue\", \"return\", \"try\", \"catch\", \"finally\", \"assert\", \"new\"],\n  types: [\"String\", \"Integer\", \"Double\", \"Float\", \"Boolean\", \"Character\", \"Object\", \"System\", \"Math\", \"List\", \"Map\", \"ArrayList\", \"HashMap\", \"Optional\", \"Stream\"],\n  constants: [\"true\", \"false\", \"null\"]\n};\n\nconst JAVA_SNIPPETS = [\n  { label: \"main\", detail: \"public static void main(…)\", insert: \"public static void main(String[] args) {\\n    \\n}\", kind: \"snippet\" },\n  { label: \"sout\", detail: \"System.out.println(…)\", insert: \"System.out.println();\", kind: \"snippet\" },\n  { label: \"class\", detail: \"public class Name { … }\", insert: \"public class Name {\\n}\", kind: \"snippet\" },\n  { label: \"for\", detail: \"for (int i = 0; …)\", insert: \"for (int i = 0; i < n; i++) {\\n    \\n}\", kind: \"snippet\" }\n];\n\nfunction activate(ctx) {\n  ctx.languages.registerLanguage({\n    id: \"java\", name: \"Java\", extensions: [\".java\"], grammar: JAVA_GRAMMAR\n  });\n\n  const items = [].concat(\n    JAVA_GRAMMAR.keywords.map(k => ({ label: k, detail: \"keyword\", kind: \"keyword\" })),\n    JAVA_GRAMMAR.control.map(k => ({ label: k, detail: \"keyword\", kind: \"keyword\" })),\n    JAVA_GRAMMAR.types.map(k => ({ label: k, detail: \"type\", kind: \"keyword\" })),\n    JAVA_SNIPPETS\n  );\n  ctx.languages.registerCompletionItemProvider(\"java\", {\n    provideCompletionItems: (prefix) => items\n  });\n\n  ctx.tasks.registerTasks([\n    { id: \"suicj.task.java.run\", label: \"Java: 编译并运行当前文件\", language: \"java\",\n      command: 'javac \"${file}\" && java ${fileBase}' },\n    { id: \"suicj.task.java.jar\", label: \"Java: 打包为可执行 jar\", language: \"java\",\n      command: 'javac \"${file}\" && jar cfe ${fileBase}.jar ${fileBase} *.class' }\n  ]);\n\n  ctx.commands.registerCommand(\"suicj.java.about\", \"Java: 关于本插件\", () => {\n    ctx.window.showInformationMessage(\"Java 语言支持 v\" + ctx.version + \" · 需要 OpenJDK 17 语言环境（官方源安装）。\");\n  });\n\n  ctx.workspace.onDidSave((file, content) => {\n    const probs = [];\n    const cls = /class\\s+([A-Z]\\w*)/.exec(content);\n    const base = file.split(\"/\").pop().replace(/\\.java$/, \"\");\n    if (cls && cls[1] !== base) {\n      probs.push({ file, severity: \"warning\", message: `public 类名 '${cls[1]}' 与文件名 '${base}.java' 不一致` });\n    }\n    ctx.diagnostics.setProblems(probs);\n  });\n\n  ctx.window.setStatusBarMessage(\"Java 已就绪\");\n}\n\nmodule.exports = { activate };\n",
      "manifest.json": "{\n  \"id\": \"suicj.lang.java\",\n  \"name\": \"Java 语言支持\",\n  \"version\": \"0.1.0\",\n  \"publisher\": \"suicoco-official\",\n  \"description\": \"Java 语法高亮、补全、代码片段与编译运行任务（依赖 java 语言环境 OpenJDK，官方源安装）。\",\n  \"engines\": { \"suicoco\": \">=0.1.0\" },\n  \"main\": \"main.js\",\n  \"requiresEnv\": \"java\",\n  \"categories\": [\"语言环境\", \"编程语言\"],\n  \"activationEvents\": [\"onLanguage:java\"],\n  \"contributes\": {\n    \"languages\": [\n      { \"id\": \"java\", \"extensions\": [\".java\"] }\n    ]\n  }\n}\n"
    }
  },
  {
    "source": "official",
    "publisher": "suicoco-official",
    "description": "Python 语法高亮、补全、代码片段与运行任务（依赖 python 语言环境，官方源安装）。",
    "downloads": 712000,
    "rating": 4.9,
    "manifest": {
      "id": "suicj.lang.python",
      "name": "Python 语言支持",
      "version": "0.1.0",
      "publisher": "suicoco-official",
      "description": "Python 语法高亮、补全、代码片段与运行任务（依赖 python 语言环境，官方源安装）。",
      "engines": {
        "suicoco": ">=0.1.0"
      },
      "main": "main.js",
      "requiresEnv": "python",
      "categories": [
        "语言环境",
        "编程语言"
      ],
      "activationEvents": [
        "onLanguage:python"
      ],
      "contributes": {
        "languages": [
          {
            "id": "python",
            "extensions": [
              ".py",
              ".pyw"
            ]
          }
        ]
      }
    },
    "files": {
      "main.js": "/* suicj.lang.python — Python 语言支持插件 */\n\"use strict\";\n\nconst PY_GRAMMAR = {\n  lineComment: \"#\",\n  blockComment: null,\n  stringQuotes: ['\"', \"'\"],\n  decorator: \"@[A-Za-z_][A-Za-z0-9_.]*\",\n  keywords: [\"def\", \"class\", \"lambda\", \"import\", \"from\", \"as\", \"global\", \"nonlocal\", \"pass\",\n    \"with\", \"assert\", \"del\", \"in\", \"is\", \"not\", \"and\", \"or\", \"yield\", \"async\", \"await\"],\n  control: [\"if\", \"elif\", \"else\", \"for\", \"while\", \"break\", \"continue\", \"return\", \"try\", \"except\", \"finally\", \"raise\"],\n  builtins: [\"print\", \"len\", \"range\", \"int\", \"str\", \"float\", \"bool\", \"list\", \"dict\", \"set\", \"tuple\",\n    \"open\", \"enumerate\", \"zip\", \"map\", \"filter\", \"sorted\", \"sum\", \"min\", \"max\", \"abs\", \"type\", \"isinstance\"],\n  types: [\"self\", \"cls\", \"object\", \"Any\", \"List\", \"Dict\", \"Optional\", \"Union\"],\n  constants: [\"True\", \"False\", \"None\"],\n  snakeIsVar: true\n};\n\nconst PY_SNIPPETS = [\n  { label: \"def\", detail: \"def name(): …\", insert: \"def name():\\n    \", kind: \"snippet\" },\n  { label: \"main\", detail: \"if __name__ == …\", insert: 'if __name__ == \"__main__\":\\n    main()', kind: \"snippet\" },\n  { label: \"class\", detail: \"class Name: …\", insert: \"class Name:\\n    def __init__(self):\\n        pass\", kind: \"snippet\" },\n  { label: \"for\", detail: \"for i in range(…)\", insert: \"for i in range(10):\\n    \", kind: \"snippet\" },\n  { label: \"print\", detail: \"print(f\\\"…\\\")\", insert: \"print(f\\\"\\\")\", kind: \"snippet\" }\n];\n\nfunction activate(ctx) {\n  ctx.languages.registerLanguage({\n    id: \"python\", name: \"Python\", extensions: [\".py\", \".pyw\"], grammar: PY_GRAMMAR\n  });\n\n  const items = [].concat(\n    PY_GRAMMAR.builtins.map(b => ({ label: b, detail: \"builtin\", kind: \"keyword\" })),\n    PY_GRAMMAR.control.map(b => ({ label: b, detail: \"keyword\", kind: \"keyword\" })),\n    PY_GRAMMAR.keywords.map(b => ({ label: b, detail: \"keyword\", kind: \"keyword\" })),\n    PY_SNIPPETS\n  );\n  ctx.languages.registerCompletionItemProvider(\"python\", {\n    provideCompletionItems: (prefix) => items\n  });\n\n  ctx.tasks.registerTasks([\n    { id: \"suicj.task.python.run\", label: \"Python: 运行当前文件\", language: \"python\",\n      command: 'python3 \"${file}\"' },\n    { id: \"suicj.task.python.venv\", label: \"Python: 创建 venv 并安装 requirements\", language: \"python\",\n      command: 'python3 -m venv .venv && .venv/bin/pip install -r requirements.txt' }\n  ]);\n\n  ctx.commands.registerCommand(\"suicj.python.about\", \"Python: 关于本插件\", () => {\n    ctx.window.showInformationMessage(\"Python 语言支持 v\" + ctx.version + \" · 需要 python 语言环境（官方源安装）。\");\n  });\n\n  ctx.workspace.onDidSave((file, content) => {\n    const probs = [];\n    if (/\\r\\n/.test(content)) probs.push({ file, severity: \"info\", message: \"检测到 CRLF，Python 建议使用 LF 行尾\" });\n    if (/^\\s* *\\t/m.test(content)) probs.push({ file, severity: \"warning\", message: \"检测到 Tab 缩进与空格混用（PEP 8 建议统一 4 空格）\" });\n    ctx.diagnostics.setProblems(probs);\n  });\n\n  ctx.window.setStatusBarMessage(\"Python 已就绪\");\n}\n\nmodule.exports = { activate };\n",
      "manifest.json": "{\n  \"id\": \"suicj.lang.python\",\n  \"name\": \"Python 语言支持\",\n  \"version\": \"0.1.0\",\n  \"publisher\": \"suicoco-official\",\n  \"description\": \"Python 语法高亮、补全、代码片段与运行任务（依赖 python 语言环境，官方源安装）。\",\n  \"engines\": { \"suicoco\": \">=0.1.0\" },\n  \"main\": \"main.js\",\n  \"requiresEnv\": \"python\",\n  \"categories\": [\"语言环境\", \"编程语言\"],\n  \"activationEvents\": [\"onLanguage:python\"],\n  \"contributes\": {\n    \"languages\": [\n      { \"id\": \"python\", \"extensions\": [\".py\", \".pyw\"] }\n    ]\n  }\n}\n"
    }
  }
];

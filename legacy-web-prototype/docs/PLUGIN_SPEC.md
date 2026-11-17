# suicoco 插件开发规范（.suicj 自有插件格式）

> 本文是 suicoco 插件（后缀 **`.suicj`**）的唯一权威规范。suicoco **不兼容** VSCode / Eclipse / 其他任何插件格式；第三方插件必须按本规范开发并转换为 `.suicj` 后，通过软件内的「第三方导入」按键导入。

## 1. 包结构

`.suicj` 是一个 **zip 容器**，根目录必须包含两个文件：

```
my-plugin.suicj          ← zip 文件，扩展名固定 .suicj
├── manifest.json        ← 必需，插件元数据与兼容性声明
├── main.js             ← 必需（路径由 manifest.main 指定），CommonJS 入口
└── (可选) README.md / 语法文件 / 片段库等静态资源
```

打包方式（任选其一）：

```bash
# 方式一：官方脚本
scripts/build-plugin.sh plugins/my-plugin

# 方式二：手工 zip（压缩方式不限，store/deflate 均支持）
cd my-plugin && zip -r ../my-plugin.suicj manifest.json main.js
```

## 2. manifest.json 规范

| 字段 | 必填 | 类型 | 说明 |
|---|---|---|---|
| `id` | ✓ | string | 全局唯一，`^[a-z0-9][a-z0-9.-]*$`，建议 `suicj.<作者>.<名称>` |
| `name` | ✓ | string | 显示名（中文可） |
| `version` | ✓ | string | semver，如 `0.1.0` |
| `main` | ✓ | string | 入口文件，必须 `.js` 结尾 |
| `engines` | ✓ | object | `{ "suicoco": ">=0.1.0" }`，不满足则拒绝安装 |
| `publisher` | — | string | 发布者；官方插件为 `suicoco-official` |
| `description` | — | string | 插件市场显示的描述 |
| `categories` | — | string[] | 如 `编程语言`、`语言环境`、`工具`、`主题` |
| `activationEvents` | — | string[] | 见 §4 |
| `contributes` | — | object | `{ "languages": [{ "id", "extensions": [".xx"] }] }`，声明本插件支持的语言与扩展名，引擎据此在打开文件时懒激活 |
| `requiresEnv` | — | string | 依赖的语言环境：`cpp` / `python` / `java`（安装器据此引导） |

manifest 示例（见 `plugins/lang-python/manifest.json`）。

**校验**：安装时强制校验必填字段、id 字符集、入口存在、engines 兼容；CI 可用 `scripts/verify-plugin.sh`。

## 3. 入口与生命周期

`main.js` 必须导出 `activate`（可选 `deactivate`），运行在受控上下文（CommonJS 风格）：

```js
"use strict";

function activate(ctx) {
  // ctx = 插件运行上下文（见 §5）
  ctx.languages.registerLanguage({ id: "go", name: "Go",
    extensions: [".go"], grammar: { /* ... */ } });
}

module.exports = { activate };
```

生命周期：`导入/安装 → installed → 满足激活事件 → activated → 贡献点生效`；
`uninstall` 时引擎自动回收该插件注册的全部贡献点。

## 4. 激活事件

| 事件 | 时机 |
|---|---|
| `onStartup` | 软件启动/插件安装后立即激活 |
| `onLanguage:<id>` | 打开匹配语言文件时激活（语言插件推荐） |
| `onCommand:<id>` | 首次调用 `registerCommand` 注册的命令时激活 |

> 缺省视为 `onStartup`。

## 5. 插件 API（ctx）

### 5.1 languages —— 语言注册与补全

```js
ctx.languages.registerLanguage({
  id: "cpp", name: "C++",
  extensions: [".cpp", ".hpp"],
  grammar: {
    lineComment: "//", blockComment: ["/*", "*/"],
    stringQuotes: ['"', "'"], preprocessor: true,  // C/C++ 风格 # 指令
    decorator: "@\\w+",                            // 可选：装饰器/注解
    keywords: ["class", "int"], control: ["if", "for"],
    types: ["std"], constants: ["true", "nullptr"], builtins: [], snakeIsVar: false
  }
});

ctx.languages.registerCompletionItemProvider("cpp", {
  provideCompletionItems(prefix) {
    return [ { label: "for", detail: "snippet", kind: "snippet", insert: "for () {\n}" } ];
  }
});
```

grammar 各字段均为**可选**；未注册语言 → 纯文本；未注册补全 → 无提示。**这就是「语言种类可后期简单开发扩展」的方式：新语言 = 新插件，零改动软件本体。**

### 5.2 tasks —— 运行任务

```js
ctx.tasks.registerTask({
  id: "suicj.task.cpp.run", label: "C++: 编译并运行当前文件",
  language: "cpp",                     // 触发时按当前文件语言过滤
  command: 'g++ -std=c++17 "${file}" -o "${fileBase}.out && ./${fileBase}.out"'
  // 变量: ${file} 当前文件路径 / ${fileBase} 去扩展名
});
```

任务在终端面板执行；若 `manifest.requiresEnv` 对应环境未安装，终端报错并引导用户到安装向导。

### 5.3 commands / window / workspace / env / diagnostics

```js
ctx.commands.registerCommand("suicj.cpp.about", "C/C++: 关于", () => {});
ctx.window.showInformationMessage("msg");
ctx.window.setStatusBarMessage("C++ 已就绪", 4000);
ctx.workspace.onDidSave((file, content) => { /* 轻量诊断 */ });
ctx.diagnostics.setProblems([{ file, line, severity: "warning", message: "…" }]);
ctx.env.isInstalled("cpp");       // 查询语言环境是否已安装
ctx.env.requestInstall("cpp");   // 请求安装向导安装环境
```

## 6. 分发渠道（仅两条）

1. **官方插件**：作者向官方插件 GitHub 仓库提交 PR（仓库地址在 suicoco 设置中配置），发布为 `release/<id>.suicj`；用户在插件市场点击安装，软件从该仓库下载。
2. **第三方导入**：开发者自行打包 `.suicj`，用户通过插件市场的「导入第三方插件 (.suicj)」按键导入。软件会做：格式检查（必须是 .suicj）→ manifest 校验 → engines 版本检查。**不做**签名/商店审核，风险由导入者自担（导入时会有来源提示）。

## 7. 开发调试建议

- 参考示例：`plugins/example-hello/`（最小插件）、`plugins/lang-*/`（语言插件完整实现）
- 打包前自检：`bash scripts/verify-plugin.sh release/my.suicj`
- 网页原型中即可测试导入：插件市场 → 导入第三方插件
- 版本升级：改 `manifest.version`；用户重复导入/安装即完成更新（同 id 覆盖）

## 8. 路线图中的规范变更

- v0.2：`contributes` 扩展声明式贡献（片段/主题用 JSON 而非 JS）
- v0.3：插件间依赖声明 `extensionDependencies` 与受控 `ctx.api` 版本化
- 变更均**向后兼容**（新增字段可选），破坏性变更将在本文件顶部标注迁移指南。

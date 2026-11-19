# suicoco 插件开发规范（`.suicj`）

> 版本 1.0 · 适用于 suicoco 0.1.0+ · 自有插件格式，不兼容任何其他格式

## 1. 总则

suicoco 是面向安卓平板的类 VSCode 代码编辑器（Kotlin + Jetpack Compose，借鉴 JCode 组织架构）。插件是扩展其语言能力、命令、任务的**唯一**方式。

- **唯一格式**：`.suicj`（本质是 zip 容器）。
- **不兼容声明**：不接受 VSCode 的 `.vsix`、JCode 的 `.jext`、或任何其他第三方扩展格式。导入非 `.suicj` 文件一律拒绝。
- **来源约束**：软件内仅开放两个入口——① 官方插件仓库下载（GitHub）；② 第三方 `.suicj` 导入按钮。
- **性能层**：插件逻辑为 JavaScript，由内置 Rhino 引擎执行；性能敏感的编辑/终端/解析由 APK 的 native 层承担，不向插件开放。

## 2. 包结构

一个 `.suicj` 是标准 ZIP（存储或 deflate 均可），根目录必须包含：

```
my-plugin.suicj  (zip)
├── manifest.json    # 必需，插件清单
├── main.js          # 必需（或 manifest.main 指定的路径）
├── grammar.json     # 可选，语法规则
└── assets/          # 可选，图标/片段等资源
```

## 3. manifest.json 字段

| 字段 | 必需 | 类型 | 说明 |
|---|---|---|---|
| `id` | ✅ | string | 形如 `publisher.name`，如 `suicj.lang.python` |
| `name` | ✅ | string | 显示名称 |
| `version` | ✅ | string | 语义化版本 `x.y.z` |
| `main` | ✅ | string | JS 入口，默认 `main.js` |
| `engines` | ✅ | object | 必须含 `"suicoco": "^0.1.0"` 等版本范围 |
| `description` | — | string | 描述 |
| `publisher` | — | string | 发布者 |
| `categories` | — | string[] | 分类 |
| `requiresEnv` | — | string | 依赖语言环境：`cpp`/`python`/`java`，安装器据此引导 |
| `activationEvents` | — | string[] | `onLanguage:c` / `onStartup` / `onCommand:xxx` |
| `contributes` | — | object | 声明式贡献点（见 §5） |

**engines 版本范围**：支持 `*`、精确版本 `0.1.0`、`^x.y.z`（同主版本）、`>=x.y.z`。不兼容时拒绝安装。

## 4. 生命周期与 ctx API

插件在 `main.js` 中导出 `activate(ctx)`。引擎调用它并注入 `ctx`：

```js
function activate(ctx) {
  ctx.languages.registerLanguage({
    id: "python", name: "Python", extensions: [".py"], grammar: {...}
  });
  ctx.tasks.registerTasks([
    { id: "py.run", label: "运行 Python", language: "python", command: 'python3 "${file}"' }
  ]);
}
module.exports = { activate };
```

可用 ctx 能力（**白名单，其他一律不可用**）：

| API | 说明 |
|---|---|
| `ctx.version` | suicoco 版本字符串 |
| `ctx.languages.registerLanguage(def)` | 注册语言（id/name/extensions/grammar） |
| `ctx.languages.registerCompletionItemProvider(lang, provider)` | 注册补全提供者 |
| `ctx.tasks.registerTasks(list)` | 注册运行任务 |
| `ctx.commands.registerCommand(id, title, handler)` | 注册命令 |
| `ctx.window.showMessage / showInformationMessage / showErrorMessage / setStatusBarMessage` | UI 提示 |
| `ctx.workspace.onDidSave(handler)` | 保存文件回调 |
| `ctx.diagnostics.setProblems(list)` | 发布诊断问题 |

## 5. 贡献点（contributes）

无需执行 JS 即可生效的声明式能力，推荐优先使用：

```json
"contributes": {
  "languages": [{ "id": "c", "extensions": [".c", ".h"] }],
  "commands": [{ "command": "suicj.about", "title": "关于" }],
  "snippets": [{ "language": "cpp", "prefix": "main", "body": ["int main(){", "  $0", "}"] }]
}
```

## 6. 语法规则（grammar.json）

轻量声明式语法，覆盖 C/C++/Python/Java 等语言：

```json
{
  "lineComment": "//",
  "blockComment": ["/*", "*/"],
  "stringQuotes": ["\"", "'"],
  "keywords": ["int", "return", "class"],
  "control": ["if", "else", "for", "while"],
  "types": ["std", "string", "vector"],
  "constants": ["true", "false", "null"]
}
```

## 7. 打包与校验

```bash
# 打包（zip 容器）
cd plugins/my-plugin && zip -r ../my-plugin.suicj manifest.json main.js grammar.json

# 校验
bash scripts/verify-plugin.sh release/my-plugin.suicj
```

校验项：后缀为 `.suicj`、zip 结构、manifest 必填字段、id/version 格式、engines 兼容性、main 文件存在、无目录穿越。

## 8. 安装来源

- **官方插件**：从 GitHub 仓库 `suidi-guliu/SuiCoCo` 的 `release/` 下载，设置中可配置仓库地址并跨会话持久化（SharedPreferences，加载/保存均经白名单净化，fail-closed）。仓库地址由 `core:config` 的 `PluginRepositoryConfig` 校验：仅允许 https、仅信任 github.com / raw / objects.githubusercontent.com 白名单域名、拒绝 IP 字面量与私网/环回地址、拒绝 user:pass 凭证、端口仅允许缺省或 443，非法地址一律拒绝（fail-closed）。插件清单地址为 `<仓库根>/release/suicj-catalog.json`。
- **官方插件清单 `suicj-catalog.json`**：`{"version":1, "name":..., "plugins":[ ... ]}`，每个条目含 `id` / `name` / `version` / `description` / `file`（相对 `release/` 的安全 `.suicj` 文件名）/ `sha256`（下载后校验）/ `engine`（版本范围，规则同 §3）/ `requiresEnv`（cpp/python/java 或 null）。由 `core:config` 的 `PluginCatalog` 解析并 fail-closed 校验（大小/条目数上限、id/文件唯一性、路径穿越拦截、sha256 格式、env 白名单），下载地址经 `PluginRepositoryConfig` 白名单构造。清单解析/校验失败即拒绝，不下载、不安装。
- **第三方插件**：通过软件内「导入 .suicj」按钮选择本地文件；导入时执行 §7 全部校验，失败即拒绝并提示原因。

## 9. 安全约束

- 无 host root：插件不能提权，不能访问 APK 私有数据之外的文件。
- ctx 是能力白名单，插件无法任意执行原生代码。
- manifest 的 `engines` 强制校验，防止跨版本不兼容。
- 目录穿越路径在解包时被拒绝。

## 10. 扩展新语言

1. 复制 `plugins/lang-python/` 为模板；
2. 改 `manifest.json` 的 id/name/extensions/requiresEnv；
3. 改 `main.js` 的 grammar 与 tasks（编译/运行命令）；
4. 按 §7 打包并放入 `release/`；
5. 语言种类可无限扩展，无需改动 APK 本体。

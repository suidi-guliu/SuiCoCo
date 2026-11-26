# suicoco 架构设计

> suicoco — 面向安卓平板的类 VSCode 代码编辑器 · 插件驱动 IDE
> 借鉴 JCode (j-code-android) 的内部组织架构，独立从零构建

## 1. 定位

suicoco 是一款 **原生安卓平板应用**（APK），提供 VSCode 风格的编码体验。软件本体**不包含**操作系统和语言环境——用户在图形化向导中按需安装 Ubuntu 精简系统与各语言工具链。

**最终产物**：`app-debug.apk` + `.suicj` 插件包。

## 2. 五层架构（严格分层）

```
┌─────────────────────────────────────────┐
│ L4 语言环境   apt 官方源：C/C++ · Python · Java │  ← 按需安装
├─────────────────────────────────────────┤
│ L3 自有插件   .suicj（zip+manifest+JS）      │  ← 官方下载 / 第三方导入
├─────────────────────────────────────────┤
│ L2 软件本体   Kotlin+Compose APK（VSCode UI） │  ← 本工程
├─────────────────────────────────────────┤
│ L1 系统环境   proot + ARM64 Ubuntu 24.04     │  ← 图形化安装，官方源
├─────────────────────────────────────────┤
│ L0 内核       安卓平板 Linux 内核（设备提供，不打包）│
└─────────────────────────────────────────┘
```

## 3. 与 JCode 的借鉴关系

| 维度 | JCode | suicoco |
|---|---|---|
| 界面 | Kotlin + Jetpack Compose | 同（VSCode Dark Modern 复刻，去 AI 入口） |
| 性能层 | C++/NDK（buffer/VT/parser） | 同思路（native:proot / native:pty） |
| 运行时 | proot + Ubuntu rootfs | 同（无 host root 硬性约束） |
| 插件 | `.jext` + Ed25519 签名 | `.suicj` 自有格式（zip+manifest+JS），不兼容其他格式 |
| 模块规则 | core 不依赖 feature，native 叶子 | 完全沿用 |
| 语言服务 | LSP/DAP（成熟） | 本阶段先做声明式高亮/补全/任务，LSP 留后续 |

## 4. Gradle 模块地图

依赖规则（沿用 JCode）：
```
:core:*     永不依赖 :feature:*
:feature:*  只依赖 :core:*
:native:*   叶子模块，产出 .so
:app        聚合一切
```

| 模块 | 职责 |
|---|---|
| `:app` | 集成层 + 主 Shell（MainActivity、MainViewModel、VSCode UI） |
| `:core:design` | VSCode 主题、配色、尺寸常量 |
| `:core:resource` | 资源与句柄管理 |
| `:core:fs` | 文件系统抽象（工作区/项目） |
| `:core:config` | 配置模型 |
| `:core:buffer` | 文本缓冲区（piece table） |
| `:core:editor` | 编辑器状态、撤销、渲染 |
| `:core:term` | PTY/VT、终端会话 |
| `:core:distro` | proot、rootfs 下载、工具链目录、apt |
| `:core:syntax` | 语法高亮引擎（grammar.json 驱动） |
| `:core:extapi` | 插件宿主 API 契约 |
| `:core:extengine` | `.suicj` 解包、校验、Rhino JS 执行 |
| `:feature:explorer` | 资源管理器 |
| `:feature:editorpane` | 编辑器标签/分组 |
| `:feature:terminalpane` | 终端面板 |
| `:feature:marketplace` | 插件市场（官方下载 + 第三方导入） |
| `:feature:onboarding` | 图形化安装向导 |
| `:feature:settings` | 设置 |
| `:native:proot` | proot JNI 桥（arm64-v8a） |
| `:native:pty` | PTY JNI 桥 |

## 5. 运行时不变量

- **无 host root**：隔离完全在 proot 用户空间（fake root `-0`）。禁止 su/libsu/Shizuku。
- **官方源**：Ubuntu rootfs 与 apt 工具链均来自官方源，安装向导中标注。
- **仅 arm64-v8a**：面向安卓平板，minSdk 33（Android 13+）。
- **插件白名单**：插件只能访问 ctx 暴露的 API，无法任意执行原生代码。

## 6. 构建

```bash
# 环境：JDK 21 / Android SDK platform 36 / NDK 27 / CMake 3.31
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

二进制 `.suicj` 插件包由 `scripts/build-plugin.sh` 从 `plugins/` 源码构建，产物在 `release/`。

## 7. 已知限制（后续阶段）

> 阶段定位说明（2026-11-18 修订）：阶段5 已由「Electron 桌面壳」修订为「安卓 proot 运行时与真实环境安装对接」——随 v0.2.0 方向修正，Electron/Web 原型（`legacy-web-prototype/`）已弃用，真实安装执行载体为本 APK 内的 proot PTY（`:core:distro` + `:native:proot` / `:native:pty`）。

- 编辑器为 Compose 文本域 + 语法着色；撤销树已于 v0.3.10 落地（`:core:editor` 分支撤销内核 + 终端/UI 接线）；minimap 于 v0.3.12 落地（`:core:editor` 的 `MinimapModel` 纯 Kotlin 缩略内核 + `EditorArea` 右侧密度条，含容量上限/视口矩形/点击行号映射内核），并于 v0.3.14 完成点击跳转接线（`MinimapModel.scrollAnchorLine` 滚动锚点内核 + `EditorArea` 外层 `verticalScroll` 承接、`lineAt → scrollAnchorLine → animateScrollTo` 平滑定位目标行居中 + `viewportRect` 视口高亮框），并于 v0.3.16 完成**拖拽连续滑动**接线（`MinimapModel.scrollAnchorFromDrag` 浮点连续锚点内核：保留小数避免跳格、截断按比例还原、视口居中、夹紧失败安全 + 37 项单测；`EditorArea` 的 `MinimapRail` 用 `pointerInput/detectVerticalDragGestures` 稳定 API 把连续 y 即时映射为 `verticalScroll` 滚动位置，点击跳转保留）；多光标编辑内核已于 v0.3.13 落地（`:core:editor` 的 `MultiCursorModel` 纯 Kotlin 内核：多光标集合管理、同列上/下放置、批量插入/退格/删除、偏移稳定与容量上限，`EditorArea` 工具栏「⋮ 多光标」开关 + 批量编辑操作条接线）。**仍缺**：BasicTextField 原生不支持多光标文本域，当前以「缓冲输入 + 批量应用」交互落地；v0.3.15 起新增**逐光标 caret 定位渲染**（`:core:editor` 的 `CursorLayoutModel` 纯 Kotlin 内核，把光标偏移映射为 0-based 行列与像素坐标，`EditorArea` 文本域用 `Box+offset` 叠加多条 caret 竖线，让每个光标位置可见）；v0.3.17 起新增**逐光标多段选区高亮**（`:core:editor` 的 `SelectionHighlightModel` 纯 Kotlin 内核：选区 (anchor,active) 规范化/夹紧/合并重叠、跨行选区逐行拆分为高亮矩形、CRLF 与 tab 同口径、token 全词出现匹配、矩形容量上限与失败安全幂等 + 96 项单测；`EditorArea` 取主光标下 token 的全部出现，用 `Box+offset+background` 叠加半透明选区块，VSCode「选中所有匹配项」视觉）。但仍非 BasicTextField 原生多选区文本域：caret 与选区均为纯内核计算 + UI 叠加的视觉指示，真正的原生多段文本选区与独立选区编辑仍属后续阶段（P2 余项）。v0.3.18 起新增**文件内查找（Find-in-file）**（`:core:editor` 的 `FindModel` 纯 Kotlin 内核：子串/忽略大小写/全词匹配、互不重叠升序的全部匹配区间、下一个/上一个循环索引与光标起点定位、匹配上限与失败安全幂等 + 41 项单测；`EditorArea` 的 Ctrl+F 查找栏即时高亮全部匹配（淡黄底 + 当前项橙底，复用 `SelectionHighlightModel` 拆分矩形），F3/Shift+F3 循环跳转并经 `CursorLayoutModel` 求行号后平滑滚动定位，复用稳定 API）。查找为纯内核 + UI 叠加，不改动编辑语义；LSP 级语义查找仍属后续（P3 余项）。v0.3.19 起新增**文件内查找替换（Find & Replace）**（`:core:editor` 的 `ReplaceModel` 纯 Kotlin 内核：单区间替换、全部匹配一次遍历替换（乱序/重叠/越界夹紧、字面量替换串、失败安全幂等）、替换后光标位置；`EditorArea` 查找栏 Ctrl+H 展开替换行，Enter 替换当前、「全部」批量替换，结果经 `MainViewModel.updateContent` 回写，撤销树可整批撤销）。替换亦为纯内核 + 现有高亮/定位复用，未引入正则或 shell 解释；LSP 级语义重命名仍属后续。v0.3.20 起新增**括号匹配高亮与跳转**（`:core:editor` 的 `BracketMatchModel` 纯 Kotlin 内核：跳过字符串/字符字面量与 `//`、`/* */` 注释后扫描代码区括号，栈式配对 `()[]{}`，按光标左右相邻偏移取配对结果，单字符半开区间可直接复用 `SelectionHighlightModel` 画矩形；不处理 `<>` 以避免泛型/比较歧义，失败安全幂等 + 35 项单测；`EditorArea` 改用 `TextFieldValue` 读取 `selection.end`，在文本域叠加亮黄配对块，Ctrl+Shift+\ 跳转到配对括号，撤销/补全/查找/多光标/minimap 既有行为不变）。该能力仍是词法级视觉指示，LSP 语义级括号/标签配对属后续。v0.3.21 起新增**代码折叠（Code Folding）**（`:core:editor` 的 `FoldingModel` 纯 Kotlin 内核：花括号配对为主、Python 缩进块为辅识别可折叠区间，跳过字符串/字符/注释，同起始行取更长覆盖、容量上限 512；`fold` 保留首行、把区间余行折为一行 `⋯ (N lines)` 占位符并按 path+行记快照，`unfold` 仅在当前文本与折叠态一致时还原、用户后续编辑则失败安全放弃，`isFolded`/`invalidate`/`clear` 供 UI 与清理 + 44 项单测；`EditorArea` 行号栏每行左侧新增折叠标记区，已折叠行 ▸ 可展开、可折叠首行 ▾ 可折叠，结果经 `MainViewModel.updateContent` 回写、撤销树整步回退，`foldTick` 触发重组）。折叠为纯文本占位替换（非 BasicTextField 视图级行折叠），撤销/补全/查找/括号匹配/多光标/minimap 既有行为不变；LSP 语义级折叠属后续。v0.3.22 起新增**行注释切换（Toggle Line Comment）**（`:core:editor` 的 `CommentModel` 纯 Kotlin 内核：按文件扩展名推断行注释符号——C/Kotlin/Java/JS 系 `//`、Python/Shell/YAML 系 `#`、Lua/SQL/Haskell 系 `--`、批处理 `REM`，默认 `//`；对光标行或选区涉及的多行，若全部非空白行均为注释则统一去注释、否则统一加注释，纯空白行跳过，保留缩进与 CRLF/LF/CR，按行增删同步光标偏移，单次行数上限 512，失败安全幂等 + 65 项单测；`EditorArea` 工具栏「// 注释」按钮与 Ctrl+/ 共用切换函数，经 `MainViewModel.updateContent` 回写、撤销树整步回退，新光标偏移随增删同步）。注释为纯文本行级切换，语言符号按扩展名启发式推断、不依赖 LSP/解析器；折叠/撤销/补全/查找/替换/括号匹配/多光标/minimap 既有行为不变；LSP 语义级注释与块注释切换属后续。v0.3.23 起新增**代码大纲（Symbol Outline）**（`:core:editor` 的 `SymbolOutlineModel` 纯 Kotlin 内核：词法扫描跳过字符串/字符与 `//`、`/* */` 注释，识别 class/interface/object/enum/struct/record、Kotlin `fun`、Python `def`、JS `function`/箭头函数、Go `func`（含方法接收者）与 `type X struct`、namespace 等声明，输出符号名/种类/行号/偏移/缩进，关键字前仅允许修饰词与 `@注解` 以防表达式误报，容量上限 512、失败安全幂等 + 45 项单测；`EditorArea` 工具栏「☰ 大纲」开关，编辑器主体右侧显示按种类着色的符号列表，点击经 `outlineJumpLine`/`outlineJumpTick` 复用查找同款滚动锚点定位到目标行并移动光标）。大纲为纯词法级符号扫描（非 LSP 语义大纲），折叠/注释/撤销/补全/查找/替换/括号匹配/多光标/minimap 既有行为不变；LSP 语义级大纲与符号重命名属后续。v0.3.24 起新增**自动缩进（Auto Indent）**（`:core:editor` 的 `AutoIndentModel` 纯 Kotlin 内核：词法级跳过字符串/字符与行尾 `//`、`#` 注释，回车时按上一行 `{}`/`[]`/`()`/`->`/`=>`/`:` 结尾自动加一级缩进、光标右侧紧跟闭合括号时反缩进、`{|}` 场景拆为两行；Tab 对齐下一制表位、Shift+Tab 退一级，沿用原 tab/空格风格与 CRLF/LF/CR 换行符，缩进列数上限 64、越界/空输入/非法配置失败安全幂等 + 单测；`EditorArea` 的 `BasicTextField` 在 `onKeyEvent` 拦截 Enter/Tab/Shift+Tab，结果经 `MainViewModel.updateContent` 回写、撤销树整步回退）。自动缩进为词法级启发式（非 LSP 格式化/语义缩进），折叠/注释/大纲/撤销/补全/查找/替换/括号匹配/多光标/minimap 既有行为不变；LSP 级格式化与语言专属缩进规则属后续。v0.3.25 起新增**跳转到指定行（Go to Line）**（`:core:editor` 的 `GoToLineModel` 纯 Kotlin 内核：解析 `行[:列]`（`:` / `,` 分隔、列可省略）、1-based→0-based、按文档行数与行尾夹紧并返回 `clamped` 标志、CRLF/LF/CR 同口径、UTF-16 偏移与 `CursorLayoutModel` 一致、非法输入失败安全幂等 + 71 项单测含 8 组交叉验证；`EditorArea` 的 Ctrl+G / 工具栏「:# 转到行」输入栏，回车确认、Esc 关闭，内核产出的光标偏移经 `CursorLayoutModel` 反算行号并复用查找/大纲同款滚动锚点定位、光标经 `TextFieldValue.selection` 移动，不改动文本，撤销/补全/minimap/查找高亮/大纲/自动缩进既有行为不变）。跳转为纯内核偏移定位（非 LSP 语义跳转），LSP 级符号跳转属后续。v0.3.26 起新增**行操作（Line Operations）**（`:core:editor` 的 `LineOperationsModel` 纯 Kotlin 内核：把文档拆为行内容与行间换行符，上移/下移整行区间、复制行、删除行，换行符按位置保留支持 CRLF/LF/CR 混排，缩进随行内容整体移动，越界/空输入/超容量失败安全，光标随块移动与增删同步 + 70 项单测；`EditorArea` 的 Alt+↑/↓、Ctrl+D、Ctrl+Shift+K 与工具栏「↑ 行 / ↓ 行 / ⧉ 复制 / ✕ 删行」，选区覆盖的多行整块处理，结果经 `MainViewModel.updateContent` 回写，撤销树整步回退、补全/minimap/查找高亮自动刷新）。v0.3.28 起新增**大小写转换（Transform Case）**（`:core:editor` 的 `CaseModel` 纯 Kotlin 内核：大写/小写/首字母大写（句首式）/标题式/驼峰/蛇形/短横线共 7 种转换，按选区或光标词处理，逐行处理并保留 CRLF/LF/CR 与选区外字符，标识符大小写边界拆分，越界/空输入/超长选区失败安全幂等 + 单测；`EditorArea` 工具栏「Aa 大小写」展开 7 项快捷栏，Ctrl+Shift+U/L 直接转大写/小写，结果经 `MainViewModel.updateContent` 回写、撤销树整步回退，新选区保持高亮便于连续切换风格）。转换为纯文本词法处理（非 LSP 语义重命名/重构），折叠/注释/大纲/撤销/补全/查找/替换/括号匹配/多光标/minimap 既有行为不变。v0.3.30 起新增**缩进指南线（Indent Guides）**（`:core:editor` 的 `IndentGuideModel` 纯 Kotlin 内核：按前导空格/tab 与 tabWidth（1..8，非法回退 4）算每行视觉缩进列，guide 列为 0/tabWidth/2*tabWidth… 且严格小于缩进宽度，空行/纯空白行跳过，`runsOf` 把相邻行同列合并为连续竖线段并按 (startLine,column) 稳定排序，行数/列数/总段数容量上限、失败安全幂等 + 56 项单测；`EditorArea` 的 `decorationBox` 在 `inner()` 后用 `Box+offset+width(1.dp)+height+background` 叠加竖线，坐标口径与 caret/选区高亮一致，随文本重组刷新、不拦截输入）。指南线为纯词法视觉指示（非 LSP 语义缩进），不写回缓冲区，折叠/注释/大纲/撤销/补全/查找/替换/括号匹配/多光标/minimap 既有行为不变。
- 多光标接线说明（v0.3.13）：`EditorArea` 的多光标操作条把 `MultiCursorModel` 的批量编辑结果经 `MainViewModel.updateContent` 回写，因此撤销树快照、补全刷新、minimap 重算均自动同步，无需额外接线。v0.3.15 逐光标 caret 定位复用同一份 `MultiCursorModel.cursors()`，v0.3.17 多段选区高亮复用主光标 token，均仅按 `cursorTick` 重组，编辑语义不变。
- 安装向导（v0.2.5 起）为**真实执行**：由 `InstallerController` 编排（下载→解压→更新源→装工具链），argv 经 `ProotManager.buildCommand` 产生。v0.3.11 起执行后端经 `:core:term` 的 `CommandExecutor` 契约注入：默认 `ProcessRunner`（ProcessBuilder，保持 feature 层零 native 依赖、可独立单测），app 层注入 `PtyCommandExecutor` 后向导与终端共享同一 `PtySessionController` 会话内核（native 优先 + 进程降级 + fail-closed + 幂等 close），逐行回传日志并支持取消。真实 proot arm64 二进制随包仍属阶段5 余项。
- 补全（v0.3.1 起）为 `CompletionEngine` **智能模糊补全**：三级打分（精确 > 前缀 > 子序列 fuzzy）、候选类型加权、动态文档词提取、光标 token 区间计算，替代原 `startsWith` 纯前缀过滤；仍未接入 LSP（P2 余项）。
- native 层（v0.2.7 起）：`:native:proot` 已实现真实 ELF64 arm64 头校验（注入二进制随包自检）；二者保持叶子模块定位。`:native:pty`（v0.3.6 起）**已实现完整 forkpty 会话链**（8 个 JNI 方法：available/open/write/read/resize/isAlive/wait/close），v0.3.7 进一步以 `PtySessionController` 封装为 fail-closed 会话编排内核（native 优先 + 进程降级 + 统一句柄 + 幂等 close），v0.3.8 由 app 层 `TerminalCommandExecutor` 接入终端命令执行（`MainViewModel.execTerminalCommand` 不再为模拟响应）。**仍未完成**：真实 proot arm64 二进制随包集成（SHA256 + RUNPATH，需 NDK 交叉编译 + arm64 真机验证，阶段5），会话内核到向导的统一复用已于 v0.3.11 完成（`CommandExecutor` 契约 + `PtyCommandExecutor` 适配，终端与向导共享 `PtySessionController`）。

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

- 编辑器为 Compose 文本域 + 语法着色；撤销树已于 v0.3.10 落地（`:core:editor` 分支撤销内核 + 终端/UI 接线）；minimap 于 v0.3.12 落地（`:core:editor` 的 `MinimapModel` 纯 Kotlin 缩略内核 + `EditorArea` 右侧密度条，含容量上限/视口矩形/点击行号映射内核），并于 v0.3.14 完成点击跳转接线（`MinimapModel.scrollAnchorLine` 滚动锚点内核 + `EditorArea` 外层 `verticalScroll` 承接、`lineAt → scrollAnchorLine → animateScrollTo` 平滑定位目标行居中 + `viewportRect` 视口高亮框），并于 v0.3.16 完成**拖拽连续滑动**接线（`MinimapModel.scrollAnchorFromDrag` 浮点连续锚点内核：保留小数避免跳格、截断按比例还原、视口居中、夹紧失败安全 + 37 项单测；`EditorArea` 的 `MinimapRail` 用 `pointerInput/detectVerticalDragGestures` 稳定 API 把连续 y 即时映射为 `verticalScroll` 滚动位置，点击跳转保留）；多光标编辑内核已于 v0.3.13 落地（`:core:editor` 的 `MultiCursorModel` 纯 Kotlin 内核：多光标集合管理、同列上/下放置、批量插入/退格/删除、偏移稳定与容量上限，`EditorArea` 工具栏「⋮ 多光标」开关 + 批量编辑操作条接线）。**仍缺**：BasicTextField 原生不支持多光标文本域，当前以「缓冲输入 + 批量应用」交互落地；v0.3.15 起新增**逐光标 caret 定位渲染**（`:core:editor` 的 `CursorLayoutModel` 纯 Kotlin 内核，把光标偏移映射为 0-based 行列与像素坐标，`EditorArea` 文本域用 `Box+offset` 叠加多条 caret 竖线，让每个光标位置可见），但仍非 BasicTextField 原生多选区文本域，真正的逐光标选区（多段选择高亮/独立选区绘制）属后续阶段（P2 余项）。
- 多光标接线说明（v0.3.13）：`EditorArea` 的多光标操作条把 `MultiCursorModel` 的批量编辑结果经 `MainViewModel.updateContent` 回写，因此撤销树快照、补全刷新、minimap 重算均自动同步，无需额外接线。v0.3.15 逐光标 caret 定位复用同一份 `MultiCursorModel.cursors()`，仅按 `cursorTick` 重组，编辑语义不变。
- 安装向导（v0.2.5 起）为**真实执行**：由 `InstallerController` 编排（下载→解压→更新源→装工具链），argv 经 `ProotManager.buildCommand` 产生。v0.3.11 起执行后端经 `:core:term` 的 `CommandExecutor` 契约注入：默认 `ProcessRunner`（ProcessBuilder，保持 feature 层零 native 依赖、可独立单测），app 层注入 `PtyCommandExecutor` 后向导与终端共享同一 `PtySessionController` 会话内核（native 优先 + 进程降级 + fail-closed + 幂等 close），逐行回传日志并支持取消。真实 proot arm64 二进制随包仍属阶段5 余项。
- 补全（v0.3.1 起）为 `CompletionEngine` **智能模糊补全**：三级打分（精确 > 前缀 > 子序列 fuzzy）、候选类型加权、动态文档词提取、光标 token 区间计算，替代原 `startsWith` 纯前缀过滤；仍未接入 LSP（P2 余项）。
- native 层（v0.2.7 起）：`:native:proot` 已实现真实 ELF64 arm64 头校验（注入二进制随包自检）；二者保持叶子模块定位。`:native:pty`（v0.3.6 起）**已实现完整 forkpty 会话链**（8 个 JNI 方法：available/open/write/read/resize/isAlive/wait/close），v0.3.7 进一步以 `PtySessionController` 封装为 fail-closed 会话编排内核（native 优先 + 进程降级 + 统一句柄 + 幂等 close），v0.3.8 由 app 层 `TerminalCommandExecutor` 接入终端命令执行（`MainViewModel.execTerminalCommand` 不再为模拟响应）。**仍未完成**：真实 proot arm64 二进制随包集成（SHA256 + RUNPATH，需 NDK 交叉编译 + arm64 真机验证，阶段5），会话内核到向导的统一复用已于 v0.3.11 完成（`CommandExecutor` 契约 + `PtyCommandExecutor` 适配，终端与向导共享 `PtySessionController`）。

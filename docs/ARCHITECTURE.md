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

- 编辑器为 Compose 文本域 + 语法着色，无 minimap/多光标/撤销树
- 安装向导原型为模拟执行，真实 debootstrap/apt 需 proot 运行时对接（阶段5）
- 补全为前缀过滤简版，未接入 LSP
- native 层（v0.2.7 起）：`:native:proot` 已实现真实 ELF64 arm64 头校验（注入二进制随包自检），`:native:pty` 已实现 `/dev/ptmx` 能力探测；二者保持叶子模块定位。**仍未完成**：真实 proot arm64 二进制随包集成、完整 forkpty 会话链（需 NDK 交叉编译 + arm64 真机验证，阶段5）

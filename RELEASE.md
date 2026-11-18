# suicoco 版本记录

## v0.2.1 — 修复 CI APK 构建（当前版本）

- **CI 修复**：移除持续故障的第三方 action `android-actions/setup-android@v3`（连续 5 次构建失败），改用 `ubuntu-latest` 预装 Android SDK（`$ANDROID_HOME`），新增 SDK 许可证接受步骤
- **插件校验**：4 个 `.suicj` 全部重新校验通过
- 后续建议（详见项目讨论区 2026-11-18 日报）：发布 v0.2.0/v0.2.1 正式 tag 与 GitHub Release；阶段4 真实安装脚本对接

## v0.2.0 — 安卓原生架构重建

> **重大方向修正**：从早期 Web/Electron 原型（已归档至 `legacy-web-prototype/`）重建为面向安卓平板的原生 APK，架构借鉴 JCode。

- **软件本体**：Kotlin + Jetpack Compose 多模块工程（20 个模块，沿用 JCode 依赖规则），VSCode Dark Modern 工作台 UI（活动栏/资源管理器/编辑器标签/面包屑/终端面板/命令面板/补全弹层/状态栏，无 AI 入口）
- **插件引擎**：`.suicj` 自有格式（zip + manifest + main.js），Rhino JS 引擎执行，manifest 强校验 + engines 兼容性 + 目录穿越防护；仅此一种格式，不兼容其他
- **语言插件**：C/C++、Python、Java + 示例，4 个 `.suicj` 产物全部通过校验
- **运行时**：proot 命令构建器（硬性无 host root）、Ubuntu 24.04 ARM64 官方 rootfs 下载器、apt 官方源工具链目录、图形化安装向导
- **native 层**：proot / pty JNI 桥骨架（arm64-v8a）
- **产物**：`app-debug.apk` + `release/*.suicj`

构建环境：JDK 21 / Android SDK platform 36 / NDK 27.0.12077973 / CMake 3.31.6 / Gradle 8.14.3。

### 已知限制（后续阶段）

- native 层为 JNI 骨架，真实 proot .so 需随包集成
- 安装向导原型为模拟执行，真实 proot/apt 脚本待对接
- 语法高亮为基础着色，补全为前缀过滤，未接入 LSP/DAP
- 编辑器为 Compose 文本域，无 minimap/多光标/撤销树

## v0.1.0 — Web 原型（已归档）

早期的 Electron/Web 原型，已移至 `legacy-web-prototype/`，仅作 UI 参考，非交付物。因方向错误（PC 桌面而非安卓平板 APK）弃用。

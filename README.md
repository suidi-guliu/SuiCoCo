# suicoco

> 面向**安卓平板**的类 VSCode 代码编辑器 · 插件驱动 IDE
> 独立从零构建，借鉴 JCode (j-code-android) 的内部组织架构

suicoco 是一款原生安卓应用（Kotlin + Jetpack Compose），提供 VSCode 风格的编码体验。软件本体**不包含**操作系统与语言环境——使用者通过图形化向导按需安装 Ubuntu 精简系统与 C/C++、Python、Java 语言环境。

**最终产物**：`app-debug.apk` + `.suicj` 插件包。

## 五层架构

```
L4 语言环境   apt 官方源：C/C++ · Python · Java（按需安装）
L3 自有插件   .suicj（zip + manifest + JS），官方下载 / 第三方导入
L2 软件本体   Kotlin + Compose APK（VSCode UI，去 AI 入口）
L1 系统环境   proot + ARM64 Ubuntu 24.04 LTS（图形化安装，官方源）
L0 内核       安卓平板 Linux 内核（设备提供，不打包）
```

## 特性

- **VSCode 风格工作台**：活动栏、资源管理器、编辑器标签、面包屑、终端面板、命令面板、IntelliSense 补全弹层、状态栏——99% 细节复刻，无 AI 入口
- **自有插件格式 `.suicj`**：zip 容器 + manifest.json + main.js，Rhino 引擎执行；**不兼容任何其他格式**
- **语言插件**：C/C++、Python、Java（语法高亮、补全、片段、运行任务），可按规范无限扩展
- **图形化安装向导**：Ubuntu 精简系统（官方 rootfs 源）+ 语言环境（apt 官方源），分层依赖、来源徽标、进度反馈
- **无 root 运行时**：借鉴 JCode，隔离完全在 proot 用户空间，硬性禁止 host root

## 工程结构（多模块 Gradle，依赖规则沿用 JCode）

```
app/                 集成层 + 主 Shell（VSCode UI）
core/                design / fs / config / buffer / editor / term / distro / syntax / extapi / extengine ...
feature/             explorer / editorpane / terminalpane / marketplace / onboarding / settings
native/              proot / pty（JNI 桥，arm64-v8a）
plugins/             C/C++ · Python · Java · 示例插件源码
release/             已打包的 .suicj 插件
docs/                架构设计 / 插件规范 / 安装向导设计
legacy-web-prototype/ 早期 Web 原型（UI 参考，非交付物）
```

依赖规则：`:core:*` 不依赖 `:feature:*`；`:feature:*` 只依赖 `:core:*`；`:native:*` 是叶子模块；`:app` 聚合一切。

## 构建

环境：JDK 21 / Android SDK platform 36 / NDK 27 / CMake 3.31

```bash
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

插件打包与校验：

```bash
for p in plugins/*/; do bash scripts/build-plugin.sh "$p"; done
bash scripts/verify-plugin.sh
```

## 插件开发

见 [docs/PLUGIN_SPEC.md](docs/PLUGIN_SPEC.md)。要点：`.suicj` 是唯一格式，第三方仅能通过软件内导入按钮导入，导入时强制校验 manifest、engines 兼容性与目录穿越防护。

## 文档

- [架构设计](docs/ARCHITECTURE.md)
- [插件开发规范](docs/PLUGIN_SPEC.md)
- [安装向导设计](docs/INSTALLER_DESIGN.md)
- [版本记录](RELEASE.md)

## GitHub

https://github.com/suidi-guliu/SuiCoCo

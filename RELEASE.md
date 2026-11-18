# suicoco 版本记录

## v0.2.6 — CI 依赖升级（消除 Node 20 deprecated 与 runner 迁移风险）

- **P2 CI 依赖升级**（2026-11-18 12:00 巡检日报新发现项）：
  1. `actions/checkout@v4` → `@v5`、`actions/setup-java@v4` → `@v5`、`actions/upload-artifact@v4` → `@v5`，统一迁移到 Node 24 运行时，消除 GitHub Actions 对 Node 20 的 deprecated 告警与被强制跑在 Node 24 的警告；
  2. 两个 job 的 `runs-on: ubuntu-latest` 固定为 `ubuntu-24.04`，避免 ubuntu-latest 迁移至 Ubuntu 26 带来的工具链（NDK 27 / CMake 3.31）兼容不确定性；
  3. 升级前已通过 GitHub REST API 确认 checkout@v5 / setup-java@v5 / upload-artifact@v5 三个 major tag 均存在（HTTP 200），升级后由 GitHub Actions 实跑验证。
- 本轮为纯 CI 配置改动，无 Kotlin/Compose 源码变更，APK 构建逻辑与 4 个插件产物不变。

## v0.2.5 — 阶段4 收尾：安装向导对接真实 PTY 执行（当前版本）

- **P0 阶段4 收尾（向导对接真实进程执行）**：将 `:feature:onboarding` 从占位实现为真实的 Compose 安装向导，取代早期"点击即完成"模拟：
  1. `core/term` 新增 `ProcessRunner`：用 ProcessBuilder 真实启动 proot 命令、合并 stdout/stderr 并**逐行回传日志**，支持按 tag 取消子进程；不依赖需 NDK 交叉编译的 native/pty JNI 骨架，CI 可编译验证。
  2. `feature/onboarding` 新增 `InstallerController`（下载→解压→apt 更新→工具链安装状态机，幂等锁、Mutex 保护、取消支持、日志上限 500 行）与 `OnboardingWizard`（VSCode Dark Modern 风格 Compose UI：步骤/进度条/逐行日志自动滚动/开始·取消按钮）。
  3. `ProotManager` 支持 `SUICOCO_PROOT` 环境变量注入 libproot.so 绝对路径（阶段5 实体化路径），否则回退 app nativeLibraryDir。
  4. **修复真实 bug**：`assertNoHostRoot` 旧版对整条 argv 做 `"su"` 子串匹配，会把 proot 路径 `com.suicoco` 内的 "su" 误判为提权、导致向导必然失败；改为跳过 argv[0] 并对裸 su/sudo 整词匹配、危险标记子串匹配。
- 本轮为纯 Kotlin/Compose 改动，native 真实 arm64 libproot.so 实体化仍属阶段5（需 NDK 交叉编译，本地无 Android SDK，留待后续）。
- 验证：`bash scripts/verify-plugin.sh` 4 个 .suicj 全部通过；Compose/Kotlin API 静态核对与括号配平通过；APK 编译以 GitHub Actions 为准。

## v0.2.4 — 阶段4 第一步：安装脚本 proot 化 + rootfs 真实解压

- **P0 阶段4 真实安装落地（第一步）**：
  1. `scripts/install-ubuntu-minimal.sh` 重写为 **proot 版**：弃用 debootstrap + sudo chroot（需 host root，违反不变量），改为下载 Ubuntu 官方 base rootfs（cdimage.ubuntu.com，arm64）+ 官方 SHA256SUMS 校验 + proot fake-root（`-0`）执行 apt-get update，全程无 su/sudo；
  2. `scripts/install-lang-env.sh` 同步重写为 proot 版：在容器内以 apt 官方源安装语言工具链，无 chroot；
  3. `core/distro` `RootfsManager.extract` 从空壳 stub 实现为真实解压：纯 Kotlin 解析 ustar/PAX/GNU tar（符号链接/硬链接/权限位），含路径穿越防护，无需外部 tar 二进制；
  4. **修复失效下载源**：`DistroProfile.UBUNTU_2404` 原 `partner-images.canonical.com` 地址已 404，改用 cdimage.ubuntu.com 官方 ubuntu-base 24.04.5 arm64 并内置 SHA-256 校验值（`download()` 下载后强制校验）；
  5. `docs/INSTALLER_DESIGN.md` 同步更新脚本迁移与解压说明。
- 本轮未替换 `:feature:onboarding` 模拟执行（Compose 向导对接 PTY 为下一步），native proot/pty 实体化仍为待办。

## v0.2.3 — v0.2.2 正式 Release 发布，阶段5 定义修订

- **P0 落地**：创建 `v0.2.2` tag 与 GitHub Release，附 `app-debug.apk` 与 4 个官方 `.suicj` 插件包（hello / C-C++ / Java / Python），产物取自 Actions RUN 44207560406 / 44207560401（双 job success）。
- **P1 阶段5 定义修订**：随 v0.2.x 弃用 Electron/Web 原型，阶段5 由「Electron 桌面壳」修订为「安卓 proot 运行时与真实环境安装对接」（native proot/pty 实体化、安装脚本 proot PTY 执行）；`docs/INSTALLER_DESIGN.md`、`docs/ARCHITECTURE.md` 同步更新。
- 本轮未改动源码，CI 保持双 job success。

## v0.2.2 — CI 全链路修复，APK 构建打通

- **CI 修复链**（连续 5 次失败的根因逐一排除）：
  1. 移除持续故障的第三方 action `android-actions/setup-android@v3`；
  2. 修复 `sdkmanager: command not found`——自包含下载 Android cmdline-tools 到 `$RUNNER_TEMP/android-sdk` 并显式加入 PATH；
  3. 补推缺失的 `gradle/wrapper/gradle-wrapper.jar`（此前二进制漏推，导致 `GradleWrapperMain` 找不到）；
  4. **补齐 37 个漏推文件**：全模块的 `AndroidManifest.xml`、`CMakeLists.txt`、`themes.xml`、`proguard-rules.pro` 及 `release/*.suicj`——这是 `processDebugMainManifest` 失败的真正根因，仓库此前从未推全。
- **验证**：GitHub Actions 两个 job 全部 success，`app-debug.apk` 成功产出，4 个 `.suicj` 校验通过。
- 后续建议（详见项目讨论区 2026-11-18 日报）：发布正式 tag 与 GitHub Release；阶段4 真实安装脚本对接；native proot 实体化。

## v0.2.1 — 修复 CI APK 构建

- 移除持续故障的第三方 action `android-actions/setup-android@v3`（连续 5 次构建失败），改用预装 SDK + 许可证接受（后因 sdkmanager 不在 PATH 由 v0.2.2 进一步完善）

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

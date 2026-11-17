# suicoco 版本记录

## v0.1.0 — 首个阶段版（架构原型）

> **发布状态**：已推送至 GitHub 公开仓库 [suidi-guliu/SuiCoCo](https://github.com/suidi-guliu/SuiCoCo)，主分支 `main`，发布提交 `c5800f4b`（`release: v0.1.0 首个阶段版`）。二进制 `.suicj` 由 CI（`.github/workflows/build-plugins.yml`）在推送后从插件源码自动构建。


- **软件本体**：VSCode Dark Modern 风格工作台（活动栏/侧边栏/标签/面包屑/状态栏/终端面板/命令面板/IntelliSense 补全弹层），无 AI 入口
- **插件引擎**：`.suicj` 自有插件格式（zip 容器）完整生命周期——安装、激活事件（onStartup / onLanguage / onCommand）、贡献点注册、卸载回收；纯 JS 解包（STORE/DEFLATE）；manifest 强制校验与 engines 版本检查
- **官方插件 ×4**：C/C++、Python、Java 语言插件（语法高亮/补全/片段/诊断/运行任务）+ Hello 示例插件；产物位于 `release/*.suicj`，全部通过 `verify-plugin.sh`
- **图形化安装向导**：Ubuntu 精简系统（debootstrap minbase 官方源）→ 语言环境（cpp/python/java，apt 官方源）分层依赖、进度与逐行日志（网页原型为模拟执行，桌面版经 IPC 执行真实脚本）
- **桌面壳骨架**：Electron main/preload，IPC 桥（安装器日志回传、官方仓库下载、第三方导入对话框）
- **规范**：`docs/PLUGIN_SPEC.md` 插件开发规范定稿（第三方仅 `.suicj`，不兼容其他格式）
- **自动化**：每日 22:00 工程进度巡检任务（有阶段性版本时自动跑 `verify-plugin.sh` 并给修改建议）

### 已知限制（下阶段处理）

- 编辑器为 textarea 叠加着色层，尚无 minimap/多光标/撤销树
- 安装向导在网页原型中为模拟执行；真实 debootstrap/chroot 在阶段5接入
- 补全为前缀过滤简版，未做模糊排序

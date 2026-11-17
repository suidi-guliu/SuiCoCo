# suicoco

> 类 VSCode 代码编辑器 · 插件驱动 IDE · 自有 `.suicj` 插件格式 · Ubuntu 精简系统图形化安装

**suicoco** 以 Ubuntu 精简系统环境为基础构建的类 VSCode 编辑器。软件本体不包含系统与语言环境——使用者通过软件内图形化向导从**官方来源**完成「Ubuntu 精简系统安装」与「语言环境安装（C/C++、Python、Java）」；插件从官方 GitHub 仓库下载，或以 `.suicj` 自有格式从第三方导入。

## 分层架构

```
L0 内核 → L1 Ubuntu 精简系统(debootstrap minbase, 官方源)
→ L2 suicoco 软件本体(编辑器+插件引擎+安装向导)
→ L3 自有插件 .suicj → L4 语言环境(gcc/python/openjdk, 官方源 apt)
```

详见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 快速体验（网页原型）

直接用浏览器打开 `app/index.html`（或经 HTTP 服务访问）：

1. **插件市场**（活动栏方块图标）→ 安装官方语言插件：C/C++、Python、Java
2. 打开 `hello.c` / `main.cpp` / `app.py` / `Hello.java` → 语法高亮/补全（`Ctrl+Space`）
3. **系统与环境安装**（圆柱图标）→ 先装 Ubuntu 精简系统 → 再装语言环境
4. `Ctrl+Shift+P` 命令面板 · `Ctrl+P` 快速打开 · 底部终端输入 `help`
5. 第三方导入：把 `release/suicj.example.hello.suicj` 拖入「导入第三方插件」即可实测 `.suicj` 解包与激活

> 网页原型的安装向导为**模拟执行**（真实安装脚本已备好，见桌面版对接差异说明）。

## 桌面版启动（阶段5，需 Node 20+ 与 Electron）

```bash
npm install        # 安装 Electron
npm start          # 启动 suicoco 桌面版
```

真实环境安装（可手动预演）：

```bash
bash scripts/install-ubuntu-minimal.sh        # Ubuntu 精简系统（官方源）
bash scripts/install-lang-env.sh python      # python 语言环境（官方源 apt）
```

## 插件生态

| 渠道 | 入口 | 说明 |
|---|---|---|
| 官方插件 | 插件市场「从 GitHub 仓库获取」 | 仓库地址在设置中配置，从 `release/` 下载 `.suicj` |
| 第三方插件 | 插件市场「导入第三方插件 (.suicj)」 | 仅支持 `.suicj` 自有格式，**不兼容其他插件格式** |

- 插件开发规范：[docs/PLUGIN_SPEC.md](docs/PLUGIN_SPEC.md)（新语言 = 新插件，无需改动软件本体）
- 当前官方插件：C/C++ · Python · Java · Hello 示例（`plugins/`，产物 `release/*.suicj`）
- 校验：`bash scripts/verify-plugin.sh`（每日巡检自动化亦调用此脚本）

## UI 说明

布局/配色/快捷键逐项复刻 VSCode Dark Modern（活动栏、侧边栏、标签、面包屑、IntelliSense 弹层、命令面板、状态栏、终端面板），**不含 AI 入口**。

## 路线图

| 阶段 | 内容 | 状态 |
|---|---|---|
| 1 | VSCode 风格编辑器壳（UI 复刻） | ✅ v0.1.0 |
| 2 | 插件引擎与 `.suicj` 格式 | ✅ v0.1.0 |
| 3 | 语言插件 C/C++ · Python · Java | ✅ v0.1.0 |
| 4 | 图形化安装向导（官方源） | 🔶 原型完成 |
| 5 | Electron 桌面壳 + 真实安装对接 | ⬜ 骨架就绪 |
| 6 | 插件市场 GitHub 仓库 + 第三方导入全流程 | ⬜ |

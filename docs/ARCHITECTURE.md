# suicoco 架构设计

> suicoco：类 VSCode 代码编辑器 · 插件驱动 · 自有 `.suicj` 插件格式
> 软件本体**不内置**系统与语言环境，全部由使用者在软件内**图形化安装**（官方来源下载）。

## 一、分层架构

```
┌─────────────────────────────────────────────────────────────┐
│ L4 语言环境        gcc/g++ · python3 · OpenJDK 17 …           │  官方源 apt 安装
│                   （由插件声明依赖，安装向导装入 L1 容器）      │
├─────────────────────────────────────────────────────────────┤
│ L3 自有插件        *.suicj（zip 容器 + manifest + main.js）    │  官方 GitHub 仓库下载
│                   实现 IDE 全部语言功能：高亮/补全/诊断/任务    │  或第三方导入（仅此格式）
├─────────────────────────────────────────────────────────────┤
│ L2 软件本体        suicoco Editor + Plugin Engine + 安装向导   │  本仓库 app/ + src-electron/
├─────────────────────────────────────────────────────────────┤
│ L1 Ubuntu 精简系统  debootstrap minbase 最小用户态              │  官方源 archive.ubuntu.com
│                   ~/.suicoco/envs/ubuntu-base                 │  图形化安装
├─────────────────────────────────────────────────────────────┤
│ L0 内核            宿主 Linux Kernel（软件本体所在机器自带，    │  不随软件分发
│                   精简系统作为容器复用宿主内核）                │
└─────────────────────────────────────────────────────────────┘
```

**核心原则**

| 原则 | 说明 |
|---|---|
| 插件即 IDE | 不装插件时 suicoco 只是纯文本编辑器；语法高亮、补全、诊断、运行任务**全部**由插件贡献。语言扩展 = 写一个新插件（无需改软件本体） |
| 环境不内置 | Ubuntu 精简系统与语言环境一律通过安装向导从**官方来源**下载安装；未安装对应环境时，插件的运行任务会引导用户前往安装向导 |
| 单一插件格式 | 只有 `.suicj`（自有格式），不兼容 VSCode/其他插件格式。软件内仅开放**官方插件下载入口**与**第三方导入按键** |
| UI 对齐 VSCode | 布局、配色（Dark Modern）、快捷键、命令面板、IntelliSense 弹层逐项复刻；**不含 AI 入口** |

## 二、目录结构

```
suicoco/
├── app/                 # 软件本体（渲染层，网页原型可直接运行）
│   ├── index.html       # 工作台骨架（标题栏/活动栏/侧边栏/编辑器/面板/状态栏）
│   ├── css/app.css      # VSCode Dark Modern 主题复刻
│   └── js/
│       ├── data.js      # 演示工作区与终端模拟数据
│       ├── zip.js       # .suicj 解包器（纯 JS zip，store/deflate）
│       ├── plugin-engine.js  # 插件引擎（安装→激活→贡献注册→卸载）
│       ├── editor.js    # 编辑器内核（grammar 驱动分词着色、多标签）
│       ├── plugin-bundle.js   # 官方插件内嵌索引（脚本生成）
│       └── app.js       # 工作台：视图/命令面板/终端/市场/安装向导
├── plugins/             # 官方插件源码（每个目录 = 一个插件）
│   ├── lang-c-cpp/  lang-python/  lang-java/  example-hello/
├── release/             # 打包好的 .suicj 产物（verify-plugin.sh 校验）
├── scripts/
│   ├── build-plugin.sh        # 打包 .suicj
│   ├── verify-plugin.sh       # 插件包结构/manifest 校验（CI 用）
│   ├── embed-plugins.py       # 生成网页演示版内嵌插件索引
│   ├── install-ubuntu-minimal.sh  # L1：debootstrap 精简系统（官方源）
│   └── install-lang-env.sh    # L4：语言环境（chroot 内 apt 官方源）
├── src-electron/        # 桌面壳（Electron main/preload，IPC 对接安装脚本）
└── docs/
    ├── ARCHITECTURE.md  # 本文件
    ├── PLUGIN_SPEC.md   # .suicj 插件开发规范
    └── INSTALLER_DESIGN.md  # 图形化安装向导设计
```

## 三、关键技术决策

1. **`.suicj` = zip 容器**：浏览器端用纯 JS 解析（`zip.js`，支持 STORE/DEFLATE），桌面端直接用 zip 工具，便于人工检查与 CI 校验。
2. **激活事件**：`onStartup` / `onLanguage:<id>` / `onCommand:<id>`（规范见 PLUGIN_SPEC），语言插件懒激活——打开匹配文件才激活，与 VSCode 一致。
3. **插件与环境联动**：manifest 声明 `requiresEnv`，安装插件时若环境缺失则引导安装向导；运行任务时终端先检查环境再执行。
4. **渲染层零依赖**：网页原型不引第三方库，保证离线可运行；Electron 壳复用同一渲染层。

## 四、版本

见 [RELEASE.md](../RELEASE.md)。当前 **v0.1.0**：网页原型 + 插件引擎 + 三个语言插件 + .suicj 生态 + 安装向导原型（模拟执行），桌面壳与真实安装对接见路线图（阶段5/6）。

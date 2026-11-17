# suicoco 图形化安装向导设计

> 目标：软件本体**不内置**系统与语言环境。使用者通过纯图形化操作完成
> 「Ubuntu 精简系统安装」与「语言环境安装」，来源一律为**官方源**。

## 1. 安装项矩阵

| 安装项 | 层次 | 官方来源 | 实现脚本 | 大小（约） |
|---|---|---|---|---|
| Ubuntu 精简系统 | L1 | `archive.ubuntu.com`（debootstrap minbase） | `scripts/install-ubuntu-minimal.sh` | 180 MB |
| C/C++ 环境 | L4 | apt：`build-essential`、`gdb` | `scripts/install-lang-env.sh cpp` | 120 MB |
| Python 环境 | L4 | apt：`python3`、`python3-pip`、`python3-venv` | `scripts/install-lang-env.sh python` | 40 MB |
| Java 环境 | L4 | apt：`openjdk-17-jdk` | `scripts/install-lang-env.sh java` | 260 MB |

## 2. 依赖与顺序约束

```
Ubuntu 精简系统 (L1)
        ├── C/C++ 环境 (L4)   ← 必须先有 L1
        ├── Python 环境 (L4)   ← 必须先有 L1
        └── Java 环境 (L4)     ← 必须先有 L1
```

- 语言环境安装前强制检查 L1 存在，缺失则引导先装底座；
- 卸载 L1 前强制要求先卸载全部语言环境；
- 语言插件（`.suicj`）声明 `requiresEnv`，安装插件 → 缺环境 → 自动引导到本向导。

## 3. 交互流程（UI）

1. 活动栏「系统与环境安装」图标（圆柱 + 下载样式）打开向导视图；
2. 每个安装项一张卡片：标题、说明、**来源徽标（官方源）**、进度条、逐行日志区；
3. 点击「安装」→ 禁用其他安装按钮（单任务队列）→ 进度条随脚本 stdout 步进；
4. 完成后卡片变为「已安装」徽标，状态栏 `环境:` 实时汇总；
5. 「重新安装 / 卸载」按钮始终可用。

## 4. 演示版与桌面版实现差异

| | 网页原型（当前） | Electron 桌面版（阶段5） |
|---|---|---|
| 进度来源 | 内置脚本台词模拟 | `spawn` 真实执行 scripts/*.sh，stdout 逐行回传（`installer:log` IPC） |
| 环境状态 | localStorage | `~/.suicoco/envs/.installed/<env>` 标记文件 |
| 提权 | — | `pkexec`/`sudo` 弹窗授权，仅安装期间提权 |

## 5. 安全与合规

- 所有下载仅走 Ubuntu 官方源（`archive.ubuntu.com` / `security.ubuntu.com`），不引入任何第三方镜像；
- 插件运行任务执行前检查对应环境标记，缺失即拒绝并引导安装；
- 安装脚本以 `set -euo pipefail` 运行，任何步骤失败即中止并回显日志。

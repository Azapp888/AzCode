# AzCode

![AzCode](1789214233836.png)

AzCode 是一组运行在**本机**的自动化助手：用自然语言描述任务，模型决策后直接操作当前设备。不同平台各自独立、功能对等，共用一个命名约定与核心机制。

## 三端与命名

| 平台 | 图形界面 | 命令行 | 分支 |
| --- | --- | --- | --- |
| Android | **magic** | **Listen** | `260912-feat-android-native` |
| Windows | **magic**（控制台） | **Listen** | `260912-feat-windows-client` |
| Linux | **magic** | **Listen** | `260912-feat-linux-native` |

- **magic** —— 有图形环境时使用的图形界面。
- **Listen** —— 无图形环境或偏好终端时使用的命令行。

同一平台的两个形态共用同一套 Agent 核心、配置与插件，行为一致。

## 核心机制

### 降缓存未命中（移植自 deepseek-harness）

大模型提供商按「消息前缀逐字节一致」复用缓存，任意位置变化都会让该点之后的缓存失效。AzCode 把系统提示拆成两段：

- **稳定前缀**：第 0 条 `system`，只放用户长期设置的人设，字节永久不变。
- **运行时上下文**：技能、记忆、插件提示单独成一条 `system` 消息；内容未变时不重复插入，变化时**追加到历史末尾**，绝不重写前缀。
- **工具定义**顺序固定、不随条件增删。

这样多轮任务可以持续命中前缀缓存，降低成本与延迟。支持在任意位置追加 `system` 消息的模型（如 deepseek-flash）会把最新一条视为完整系统提示，旧的那条自然被取代。

### 插件市场

- **内置 ponytail**：首次启动写入「拒绝过度设计」技能，约束 Agent 只做最小必要改动。
- **扫描热门插件**：按关键词搜索 GitHub 上 star 较多的 Agent 技能仓库，一键安装其中的 `SKILL.md`。
- **Agent 自助**：模型也可通过工具自行搜索、安装、启停、删除插件。

## 各端入口

### Android（`260912-feat-android-native`）

原生应用（Kotlin）：无障碍 / Shizuku / Root 直接操作手机，内置聊天界面、多模型提供商、文生图、完整 Markdown、技能与插件管理、内联问答区。附带 `127.0.0.1:8848` 能力桥与 Listen 命令行。

```bash
# Listen 命令行（电脑侧，需 adb 连接手机）
curl -fsSL https://raw.githubusercontent.com/Azapp888/AzCode/260912-feat-android-native/tools/install.sh | bash
listen run "打开设置查看 Android 版本号"
```

### Windows（`260912-feat-windows-client`）

原生应用（.NET 8 / WPF）：UI Automation 读屏、模拟键鼠、PowerShell 执行，直接控制这台电脑。

```powershell
dotnet build src/AzCode.Desktop/AzCode.Desktop.csproj -c Release
```

### Linux（`260912-feat-linux-native`）

Python 实现，同一入口按桌面环境自动选择 **magic**（tkinter GUI）或 **Listen**（CLI）。

```bash
curl -fsSL https://raw.githubusercontent.com/Azapp888/AzCode/260912-feat-linux-native/linux/install.sh | bash
listen chat          # 命令行
magic                # 图形界面
```

## 架构

```
magic / Listen（各端 UI）
        │
        ▼
   Agent 决策循环 ── PromptCache（稳定前缀 + 运行时上下文追加）
        │
        ├── 大模型 chat/completions（OpenAI 兼容 function calling）
        └── 设备能力
              ├── Android：无障碍 / Shizuku / Root
              ├── Windows：UI Automation / SendInput / PowerShell
              └── Linux：bash / 文件 / xdotool / 截图
```

## 许可

MIT。各端 README 见对应分支。

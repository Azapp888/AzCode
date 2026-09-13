# AzCode Console (Windows)

Windows 原生端（.NET 8 / WPF）：运行在本机的自动化 Agent。它把自然语言任务交给模型决策，再通过 Windows UI Automation、模拟鼠标键盘与 PowerShell 直接操作这台电脑。

应用名称仍为 **AzCode**。图形界面形态打标签 **magic**，命令行形态打标签 **Listen**——标签用于区分形态，不作为应用显示名。

其余端位于独立分支、功能对等：Android `260912-feat-android-native`、Linux `260912-feat-linux-native`。

## 架构

```
AzCode Console (.NET/WPF)
  ┌────────────────────────────────────────────┐
  │ DeepSeek chat/completions                  │
  │  tools: get_screen/click/type/key/shell…   │
  │ AgentRunner 决策循环                        │
  └───────────────┬────────────────────────────┘
                  │ 直接调用（无中间服务）
                  ▼
  ┌────────────────────────────────────────────┐
  │ WindowsAutomation                          │
  │  UI Automation 读屏 / SendInput 键鼠 /      │
  │  PowerShell 执行                            │
  └────────────────────────────────────────────┘
```

## 前置条件

- Windows 10/11
- .NET 8 SDK（开发）或下载自包含发布包（运行）
- DeepSeek API Key

## 使用

1. 打开 AzCode Console，填写 DeepSeek Key、Base URL、模型与最大步数，点「保存配置」。
2. 输入任务描述，点「运行任务」。
3. 在日志区观察每一步的读屏、决策与执行过程；可随时点「停止」。

配置保存在 `%APPDATA%\AzCode\config.json`。也可用环境变量 `AZCODE_DEEPSEEK_API_KEY` 提供 Key（优先于配置文件）。

## Agent 可用工具

| 工具 | 说明 |
| --- | --- |
| `get_screen` | 读取当前活动窗口的控件树（名称/类型/坐标）与窗口标题 |
| `click` | 按控件名称点击，或按屏幕坐标点击 |
| `type` | 向当前焦点输入文本 |
| `key` | 按下组合键，如 `ctrl+s`、`enter`、`alt+f4`、`win` |
| `shell` | 执行 PowerShell 命令 |
| `search_plugins` / `install_plugin` / `list_installed_plugins` / `set_plugin_enabled` / `remove_plugin` | 插件市场：扫描热门开源插件并一键安装 |
| `finish` | 结束任务并总结 |

## 降缓存未命中与插件

- **降缓存未命中**（移植自 deepseek-harness）：第 0 条 `system` 为稳定人设，字节不变；技能等运行时上下文单独成一条 `system` 消息，未变时不重复插入、变化时追加到历史末尾；工具定义顺序固定。历史持久化在 `%APPDATA%\AzCode\conversation.json`，append-only 增长以保持前缀缓存温热。实现见 `Services/PromptCache.cs`。
- **内置 ponytail**：首次启动写入「拒绝过度设计」技能，默认启用。
- **插件市场**：模型可搜索 GitHub 上 star 较多的 Agent 技能仓库，一键安装其中的 `SKILL.md`。

## 构建

```powershell
dotnet build src/AzCode.Desktop/AzCode.Desktop.csproj -c Release

dotnet publish src/AzCode.Desktop/AzCode.Desktop.csproj -c Release `
  -r win-x64 --self-contained true -p:PublishSingleFile=true -o dist
```

在非 Windows 主机上交叉编译需追加 `-p:EnableWindowsTargeting=true`。

CI（`.github/workflows/windows.yml`）在 `windows-latest` 上构建并发布自包含单文件 exe，打 `windows-v*` tag 时发布 Release。

## 目录

```
src/AzCode.Desktop/
  App.xaml / MainWindow.xaml        界面
  MainWindow.xaml.cs                配置 / 运行 / 停止
  Models/AppConfig.cs               配置读写
  Models/ChatModels.cs              对话消息模型
  Services/DeepSeekClient.cs        DeepSeek function calling
  Services/AgentRunner.cs           决策循环与工具执行
  Services/PromptCache.cs           降缓存未命中：稳定前缀 + 运行时上下文追加
  Services/SkillStore.cs            技能持久化 + 内置 ponytail + 插件扫描安装
  Services/ConversationStore.cs     会话历史 append-only 持久化
  Services/WindowsAutomation.cs     UI Automation / 键鼠 / PowerShell
.github/workflows/windows.yml
```

## 许可

MIT。DeepSeek 决策循环参考 OpenAI 兼容 function calling 规范实现。

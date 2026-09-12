# AzCode Console (Windows)

Windows 原生端（.NET 8 / WPF）：Agent 的「大脑与编排」。它把自然语言任务交给 DeepSeek 决策，再经 `adb forward` 调用 Android 端的能力桥，驱动手机完成多步操作。

Android 端（设备能力桥）位于独立分支 `260912-feat-android-native`。

## 架构

```
AzCode Console (.NET/WPF)                    Android (app.azcode.bridge)
  ┌───────────────────────────┐             ┌──────────────────────────┐
  │ DeepSeek chat/completions │             │ AgentBridge              │
  │  tools: tap/swipe/shell…  │ ── HTTP ──▶ │  127.0.0.1:8848         │
  │ AgentRunner 决策循环       │ adb forward │  /screen /tap /shell …  │
  └───────────────────────────┘             └──────────────────────────┘
```

## 前置条件

- Windows 10/11
- .NET 8 SDK（开发）或下载自包含发布包（运行）
- `adb`（platform-tools），或使用 Android 端同分支提供的方案
- 一台已安装并启动 AzCode Bridge 的 Android 手机，USB 调试已授权
- DeepSeek API Key

## 使用

1. 手机端启动 AzCode Bridge，开启无障碍服务，按需授权 Shizuku/Root。
2. 打开 AzCode Console，填写 `adb` 路径与端口，点「连接设备」（自动执行 `adb forward tcp:8848 tcp:8848` 并探测 `/health`）。
3. 填写 DeepSeek Key 与模型，点「保存配置」。
4. 输入任务描述，点「运行任务」，在日志区观察决策与执行过程。

配置保存在 `%APPDATA%\AzCode\config.json`。也可用环境变量 `AZCODE_DEEPSEEK_API_KEY` 提供 Key（优先于配置文件）。

## Agent 可用工具

| 工具 | 说明 |
| --- | --- |
| `get_screen` | 读取当前屏幕节点（文本/坐标/可点击性） |
| `tap` | 坐标或按文本点击 |
| `swipe` | 滑动 |
| `global` | back / home / recents / notifications |
| `shell` | 以 Shizuku/Root 身份执行命令（需高权限模式） |
| `finish` | 结束任务并总结 |

## 构建

```powershell
dotnet build src/AzCode.Desktop/AzCode.Desktop.csproj -c Release

dotnet publish src/AzCode.Desktop/AzCode.Desktop.csproj -c Release `
  -r win-x64 --self-contained true -p:PublishSingleFile=true -o dist
```

CI（`.github/workflows/windows.yml`）在 `windows-latest` 上构建并发布自包含单文件 exe，打 `windows-v*` tag 时发布 Release。

## 目录

```
src/AzCode.Desktop/
  App.xaml / MainWindow.xaml        界面
  MainWindow.xaml.cs                连接 / 运行 / 停止
  Models/AppConfig.cs               配置读写
  Models/ChatModels.cs              对话消息模型
  Services/AdbRunner.cs             adb 调用与端口转发
  Services/DeviceBridgeClient.cs    能力桥 HTTP 客户端
  Services/DeepSeekClient.cs        DeepSeek function calling
  Services/AgentRunner.cs           决策循环与工具执行
.github/workflows/windows.yml
```

## 许可

MIT。DeepSeek 决策循环参考 OpenAI 兼容 function calling 规范实现。

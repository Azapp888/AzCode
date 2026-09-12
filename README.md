# AzCode (Android)

Android 原生端独立应用：在手机上输入自然语言任务，内置 DeepSeek 决策循环，直接调用本机无障碍 / Shizuku 能力操作手机。**不依赖 Windows 端即可独立运行**。

同时内置一个仅绑定回环（`127.0.0.1:8848`）的 HTTP 能力桥，Windows 端（独立分支 `260912-feat-windows-client`）可经 `adb forward` 复用同一套设备能力。

## 独立运行（主要用法）

1. 打开无障碍服务：应用内「无障碍设置」→ 系统设置里开启 **AzCode Screen Control**。
2. 填写 **DeepSeek API Key**（Base URL 与模型有默认值，可改），点「保存配置」。
3. 输入任务（如「打开设置查看 Android 版本号」），点「运行任务」，日志区实时显示决策与执行。
4. 需要 shell 能力时：安装 Shizuku 并授权，或用「切换模式」切到 ROOT；平时 NORMAL 模式仅用无障碍能力。

Agent 工具集（与 Windows 端一致）：

| 工具 | 说明 |
| --- | --- |
| `get_screen` | 读取当前屏幕节点（文本/坐标/可点击性） |
| `tap` | 坐标或按文本点击 |
| `swipe` | 滑动 |
| `global` | back / home / recents / notifications |
| `shell` | 以 Shizuku/Root 身份执行命令（需高权限模式） |
| `finish` | 结束任务并总结 |

## 架构

```
Android (app.azcode.bridge)
  MainActivity ── AgentRunner ── DeepSeekClient ──▶ DeepSeek API
                      │
                      ├── AzAccessibilityService  读屏 / 点击 / 滑动 / 全局动作
                      └── DeviceControl           Shizuku / Root shell 通道

  （可选）AgentBridge 127.0.0.1:8848 ── adb forward ──▶ Windows 端
```

## 能力桥 API（可选，供 Windows 端调用）

| 方法 | 路径 | Body / 说明 |
| --- | --- | --- |
| GET | `/health` | 返回 accessibility / shizuku / root / mode 能力探测 |
| GET | `/screen` | 当前窗口可见节点（text/desc/cls/x/y/w/h/clickable），上限 200 |
| POST | `/tap` | `{"x":123,"y":456}` 或 `{"text":"确定"}` |
| POST | `/swipe` | `{"x1":..,"y1":..,"x2":..,"y2":..,"duration":300}` |
| POST | `/global` | `{"action":"back"\|"home"\|"recents"\|"notifications"}` |
| POST | `/shell` | `{"cmd":"pm list packages"}`，需 SHIZUKU 或 ROOT 模式 |
| POST | `/notify` | `{"title":"..","body":".."}` |

## 权限模式

| 模式 | `/shell` 通道 | 前置条件 |
| --- | --- | --- |
| NORMAL | 不可用（返回错误说明） | 无 |
| SHIZUKU | Shizuku binder，以 adb(uid 2000) 身份执行 | 安装并启动 Shizuku，应用内授权 |
| ROOT | `su -c`，以 uid 0 执行 | 设备已 Root |

读屏与点击依赖无障碍服务，需在系统设置手动开启「AzCode Screen Control」。

## 构建

本地需要 JDK 17 + Android SDK。仓库不含 `gradle-wrapper.jar`，使用系统 `gradle`：

```bash
gradle assembleDebug
```

或直接推送，`.github/workflows/android.yml` 会在 GitHub Actions 上构建 Debug / Release(未签名) APK 并上传产物；打 `android-v*` tag 时发布 Release。

## 目录

```
app/src/main/java/app/azcode/bridge/
  MainActivity.kt            模型配置 + 任务输入 + 运行日志 + 能力开关
  AgentRunner.kt             设备端 Agent 决策循环与工具执行
  DeepSeekClient.kt          DeepSeek function calling（JDK 标准库）
  AgentConfig.kt             API Key / Base URL / 模型配置持久化
  AzAccessibilityService.kt  读屏 / 点击 / 滑动 / 全局动作
  DeviceControl.kt           权限模式 + Shizuku/Root shell 执行
  AgentBridge.kt             127.0.0.1:8848 环回 HTTP 能力桥（供 Windows 端）
  BridgeService.kt           specialUse 前台服务保活桥接
app/src/main/res/xml/azcode_accessibility_service.xml
.github/workflows/android.yml
```

## 来源与许可

MIT。无障碍与 Shizuku 执行模块参考并改编自
[Soodok/Deepseek-Harness-Local-Android](https://github.com/Soodok/Deepseek-Harness-Local-Android)（MIT），
Shizuku API 来自 [RikkaApps/Shizuku-API](https://github.com/RikkaApps/Shizuku-API)。

## 免责声明

SHIZUKU / ROOT 模式下 Agent 可执行系统级命令，误操作可能损坏设备或数据。请仅在本人拥有或获得明确授权的设备上使用。默认 NORMAL 模式不提供 shell 通道。

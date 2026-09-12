# AzCode Bridge (Android)

Android 原生端：把手机变成可被 Agent 驱动的「执行手」。应用在设备上跑一个仅绑定回环的 HTTP 能力桥，向 Agent 暴露无障碍读屏/点击/滑动、全局导航、系统通知，以及 Shizuku / Root 级 shell 通道。

Windows 端（Agent 大脑与编排）位于独立分支 `260912-feat-windows-client`，通过 `adb forward` 连接本桥。

## 架构

```
Windows Agent Console (.NET/WPF)          Android  (app.azcode.bridge)
  DeepSeek 决策循环  ── adb forward ──▶  127.0.0.1:8848  AgentBridge
                                                    ├── /screen  AzAccessibilityService
                                                    ├── /tap     AzAccessibilityService
                                                    ├── /shell   DeviceControl (Shizuku/Root)
                                                    └── /notify  NotificationManager
```

## 能力桥 API

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

## 连接方式

```bash
# 手机开启 USB 调试并连接后，Windows 端执行端口转发
adb forward tcp:8848 tcp:8848

# 验证
curl http://127.0.0.1:8848/health
```

## 构建

本地需要 JDK 17 + Android SDK。仓库不含 `gradle-wrapper.jar`，使用系统 `gradle`：

```bash
gradle assembleDebug
```

或直接推送，`.github/workflows/android.yml` 会在 GitHub Actions 上构建 Debug / Release(未签名) APK 并上传产物；打 `android-v*` tag 时发布 Release。

## 目录

```
app/src/main/java/app/azcode/bridge/
  MainActivity.kt            状态面板 + 服务/权限开关
  BridgeService.kt           前台服务（specialUse）保持桥接常驻
  AgentBridge.kt             127.0.0.1:8848 环回 HTTP 能力桥
  AzAccessibilityService.kt  读屏 / 点击 / 滑动 / 全局动作
  DeviceControl.kt           权限模式 + Shizuku/Root shell 执行
app/src/main/res/xml/azcode_accessibility_service.xml
.github/workflows/android.yml
```

## 来源与许可

MIT。无障碍与 Shizuku 执行模块参考并改编自
[Soodok/Deepseek-Harness-Local-Android](https://github.com/Soodok/Deepseek-Harness-Local-Android)（MIT），
Shizuku API 来自 [RikkaApps/Shizuku-API](https://github.com/RikkaApps/Shizuku-API)。

## 免责声明

SHIZUKU / ROOT 模式下 Agent 可执行系统级命令，误操作可能损坏设备或数据。请仅在本人拥有或获得明确授权的设备上使用。默认 NORMAL 模式不提供 shell 通道。

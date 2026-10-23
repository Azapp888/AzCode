# AzCode (Android)

[English](en.md) | [日本語](ja.md) | [한국어](ko.md) | [Français](fr.md) | [Deutsch](de.md) | [Русский](ru.md) | [Español](es.md)

AzCode 是运行在 Android 手机本地的自动化助手。用自然语言描述任务，应用内置 Agent 决策循环，通过无障碍服务读取屏幕、点击控件、滑动与执行命令，直到任务完成。数据全部保存在本机，不依赖云同步。

- 应用名：**AzCode**
- 图形界面形态标签：**magic**
- 命令行形态标签：**Listen**
- 当前版本：**1.9.15**（versionCode 70）
- 系统要求：Android 8.0 及以上（minSdk 26），targetSdk 34

应用可以独立运行，也可以开启仅绑定 `127.0.0.1:8848` 的能力桥，经 `adb forward` 供 Listen 命令行或其他端复用同一套设备能力。

## 功能总览

| 能力 | 说明 |
| --- | --- |
| 自然语言任务 | 聊天式输入，Agent 多步规划与执行，实时展示工具调用 |
| 屏幕操作 | 读取节点、按文本或坐标点击、滑动、系统导航、Unicode 文本输入 |
| 应用启动 | 按包名或应用名启动，内置 shell 与无障碍桌面点击的降级策略 |
| 命令执行 | Termux、内置 shell、Shizuku、Root 四种通道，按权限模式自动选择 |
| 多模型接入 | 多个提供商账号，支持 OpenAI 兼容、Anthropic、Gemini 协议 |
| 思考深度 | 关闭、快速、标准、深度四档，按模型能力自动处理参数兼容 |
| 会话管理 | 独立会话、历史抽屉、自动标题、上下文续接与长上下文压缩 |
| 附件 | 图片、音频、PDF、Office、文本与代码 |
| 语音输入 | 系统离线识别与讯飞在线识别可切换 |
| 悬浮助理 | 系统默认助理入口、透明悬浮光晕、页面内提问与键盘输入 |
| 长期记忆 | 敏感信息、使用习惯、技能方法、本机个性化四类 |
| 技能与插件 | 内置技能、自定义技能、GitHub SKILL.md 安装、热门插件扫描 |
| GitHub | 读写文件、浏览分支与提交、管理 Issue 与 Pull Request |
| 文生图 | 多提供商生图模型，结果直接在聊天中展示并可保存 |
| Office 文档 | 通过 GenOffice MCP 生成 docx / xlsx / pptx 并预览 |
| 输入法 | 内置 AzCode 输入法，为 Agent 提供 Unicode 文本写入兜底 |
| 能力桥 | 环回 HTTP API 与 Listen 命令行 |
| 运行日志 | 崩溃与运行日志落盘，设置页可查看、复制、清空 |

## 快速开始

### 安装

从 GitHub Releases 的 `android-v*` 版本下载 APK；日常构建产物也可以在 GitHub Actions 的 `azcode-magic-apk` 工件中获取。debug 与 release 使用仓库内同一把签名密钥，可以互相覆盖安装。

### 首次配置

1. 打开「设置 → 设备能力 → 无障碍设置」，在系统设置中开启 **AzCode Screen Control**。
2. 进入「设置 → 模型管理」，添加并启用一个模型提供商，填写 API Key；Base URL 与模型可选用预设模板。
3. 返回主界面，在模型选择器中确认当前模型。
4. 输入任务并发送，例如「打开设置查看 Android 版本号」。

### 权限

| 权限 | 用途 |
| --- | --- |
| 无障碍服务 | 读屏、点击、滑动、系统导航、文本写入 |
| 麦克风 | 语音输入与悬浮助理 |
| 通知 | 任务完成提醒与提问通知 |
| 显示在其他应用上层 | 悬浮助理光晕 |
| 相机 | 拍照附件 |
| Termux RUN_COMMAND | 通过 Termux 执行完整 Linux 命令 |
| Shizuku | 以 adb 身份执行系统级命令 |

### 从源码构建

需要 JDK 17 与 Android SDK。仓库不包含 `gradle-wrapper.jar`，使用系统 `gradle`：

```bash
gradle assembleDebug
gradle assembleRelease
```

推送代码后，`.github/workflows/android.yml` 会在 GitHub Actions 上构建 Debug 与 Release APK。推送 `android-v*` 标签时会创建 GitHub Release。

## 设备能力与命令执行

| 模式 | 通道 | 身份 | 前置条件 | 超时 |
| --- | --- | --- | --- | --- |
| NORMAL + Termux | Termux `RUN_COMMAND` | Termux uid | 安装 Termux、授予 RUN_COMMAND、设置 `allow-external-apps=true` | 90 秒 |
| NORMAL + 内置 shell | `/system/bin/sh -c` | 应用 uid | 无 | 60 秒 |
| SHIZUKU | Shizuku binder | adb uid 2000 | 启动 Shizuku 并授权 | 60 秒 |
| ROOT | `su -c` | uid 0 | 设备已 Root | 60 秒 |

NORMAL 模式优先尝试 Termux，未就绪时回退内置 shell。访问其他应用私有目录、修改系统设置等操作需要 Shizuku 或 Root。

## 本地数据与存储

AzCode 没有 SQLite、Room 或服务端数据库，数据层由应用私有 `SharedPreferences` 与 JSON 文件组成：

| 路径 | 内容 |
| --- | --- |
| `SharedPreferences` | 模型提供商、会话游标、技能、命令记忆、GitHub / GenOffice 配置、语音与权限模式 |
| `files/sessions/<id>.json` | 单个会话的聊天记录与 Agent 上下文 |
| `files/memory.json` | 长期记忆条目 |
| `files/logs/YYYY-MM-DD.log` | 按天轮转日志，最多 10 个文件，单文件上限 512 KB |
| `files/genoffice/` | 生成的 Office 文档 |
| `cache/az_images/`、`cache/captures/` | 图片缓存与拍照临时文件 |

会话在写盘时采用临时文件加 rename，Agent 上下文最多保留 40 条消息，更早内容通过摘要压缩保留。卸载应用会删除全部本地数据。

## 能力桥与 Listen CLI

桥接服务仅监听 `127.0.0.1:8848`，不对外网开放，也没有鉴权。跨机访问必须经 `adb forward`。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/health` | 返回无障碍、Shizuku、Root 与权限模式状态 |
| GET | `/screen` | 当前屏幕可见节点，最多 200 条 |
| POST | `/tap` | `{"x":123,"y":456}` 或 `{"text":"确定"}` |
| POST | `/swipe` | `{"x1":..,"y1":..,"x2":..,"y2":..,"duration":300}` |
| POST | `/global` | `{"action":"back"}`，支持 back / home / recents / notifications |
| POST | `/shell` | `{"cmd":"pm list packages"}`，按当前权限模式执行 |
| POST | `/notify` | `{"title":"..","body":".."}` |
| POST | `/agent` | `{"task":"..","session":"可选会话 id"}`，返回 `answer` 与 `events` |

安装并连接手机后：

```bash
curl -fsSL https://raw.githubusercontent.com/Azapp888/AzCode/main/tools/install.sh | bash
listen run "打开设置查看 Android 版本号"
listen screen
listen tap --text "确定"
```

## 隐私与安全

- 模型 API Key、GitHub Token、GenOffice 令牌与讯飞凭据仅保存在应用私有存储中，应用不读取环境变量。
- 本机个性化快照只在设备本地生成，不注入云端对话上下文。
- 能力桥仅绑定环回地址，跨机访问依赖 adb 转发。
- 讯飞在线识别会把语音发送到讯飞开放平台；系统内置识别优先使用设备端离线能力。
- Shizuku 与 Root 可以执行系统级命令，误操作可能损坏设备或数据，请仅在本人拥有或获得明确授权的设备上使用。

## 已知限制

- 读屏与界面操作依赖无障碍服务，部分厂商系统会限制后台无障碍服务存活。
- 系统级 shell 能力依赖 Shizuku 或 Root，NORMAL 模式受应用沙箱限制。
- 音频与图片输入依赖所选模型的多模态能力。
- 文生图依赖厂商生图 API，各平台支持的尺寸与数量不同。
- 应用不提供云同步，数据随应用卸载删除。

## 相关链接

- 完整文档：根目录 `README.md`
- 产品说明：`PRODUCT.md`
- 设计系统：`DESIGN.md`
- 命令行工具：`tools/listen` 与 `tools/install.sh`

## 许可

MIT。无障碍与 Shizuku 执行模块参考并改编自 [Soodok/Deepseek-Harness-Local-Android](https://github.com/Soodok/Deepseek-Harness-Local-Android)（MIT）。Shizuku API 来自 [RikkaApps/Shizuku-API](https://github.com/RikkaApps/Shizuku-API)。

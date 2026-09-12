# AzCode (Android)

Android 原生端独立应用：在手机上输入自然语言任务，内置 DeepSeek 决策循环，直接调用本机无障碍 / Shizuku 能力操作手机。**不依赖 Windows 端即可独立运行**。

同时内置一个仅绑定回环（`127.0.0.1:8848`）的 HTTP 能力桥，Windows 端（独立分支 `260912-feat-windows-client`）可经 `adb forward` 复用同一套设备能力。

## 独立运行（主要用法）

1. 打开无障碍服务：点右上角齿轮进入「设置」→「无障碍设置」→ 在系统设置里开启 **AzCode Screen Control**。
2. 在「设置」里填写 **DeepSeek API Key**（Base URL 与模型有默认值，可改），点「保存配置」。
3. 返回聊天界面，输入任务（如「打开设置查看 Android 版本号」）并发送。
4. 需要 shell 能力时：安装 Shizuku 并授权，或在「设置」里用「切换权限模式」切到 ROOT；平时 NORMAL 模式仅用无障碍能力。

### 界面

- **聊天式对话**：用户消息靠右（蓝色气泡），助手回复靠左（灰色气泡）。
- **工具调用折叠框**：每一步实际执行的工具（读屏、点击、滑动、命令等）以可折叠卡片展示，默认收起；点击标题展开查看参数与结果，执行失败时自动展开。
- **思考指示器**：等待模型响应时显示「正在思考…」。
- **设置页**：模型配置、系统提示词、技能管理、无障碍/Shizuku/Root 状态与开关、桥接启停、权限模式切换全部收敛到设置页，主界面保持纯净。

### 附件

聊天输入框左侧回形针按钮可添加附件，支持以下类型（按 DeepSeek API 能力分类处理）：

| 类型 | 扩展名 | 处理方式 |
| --- | --- | --- |
| 图片 | jpg / jpeg / png / gif / webp | base64 内联，走 DeepSeek 视觉输入 |
| PDF | pdf | 本地用 `PdfRenderer` 渲染页面为 JPEG 后作为图片发送（最多 8 页） |
| Office | docx / xlsx / pptx | 本地解包提取文本后并入消息文本 |
| 文本/代码 | txt / md / csv / json / xml / html / 多种源码 | 直接读取为文本 |

旧版二进制 Office（.doc/.xls/.ppt）不受支持，请另存为 OOXML 格式。图片输入需使用支持视觉的模型（默认 `deepseek-flash`）。

### 系统提示词与技能

- **系统提示词**：在设置页填写，留空使用内置默认提示词；会作为每次运行的 system 消息。
- **技能管理**：设置页 → 技能管理。每个技能是一段注入系统提示词的指令文本，可单独启用/停用或删除。
- **从 GitHub 安装技能**：点技能页右上角「+」，填入 SKILL.md 的 GitHub 链接即可。支持：

```
https://raw.githubusercontent.com/owner/repo/main/path/SKILL.md
https://github.com/owner/repo/blob/main/path/SKILL.md
https://github.com/owner/repo/tree/main/subdir        （取该目录下 SKILL.md）
https://github.com/owner/repo                         （取默认分支根目录 SKILL.md）
owner/repo
```

安装时会解析 SKILL.md 的 YAML frontmatter（`name` / `description`），没有则取首个标题与首行正文。

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
  MainActivity.kt            聊天界面：气泡消息 + 可折叠工具卡片 + 附件
  SettingsActivity.kt        设置页：模型/系统提示词 + 设备能力 + 桥接/权限模式
  SkillsActivity.kt          技能管理：启停、删除、从 GitHub 安装
  AgentRunner.kt             设备端 Agent 决策循环，向 UI 输出结构化事件
  DeepSeekClient.kt          DeepSeek function calling + 多模态内容构造
  AgentConfig.kt             Key / Base URL / 模型 / 最大步数 / 系统提示词持久化
  SkillStore.kt              技能持久化
  GitHubSkillFetcher.kt      GitHub SKILL.md 下载与解析
  AttachmentReader.kt        附件读取：图片/PDF/Office/文本
  AzAccessibilityService.kt  读屏 / 点击 / 滑动 / 全局动作
  DeviceControl.kt           权限模式 + Shizuku/Root shell 执行
  AgentBridge.kt             127.0.0.1:8848 环回 HTTP 能力桥（供 Windows 端）
  BridgeService.kt           specialUse 前台服务保活桥接
app/src/main/res/layout/     聊天页 / 设置页 / 技能页 / 各类消息卡片
app/src/main/res/xml/azcode_accessibility_service.xml
.github/workflows/android.yml
```

## 来源与许可

MIT。无障碍与 Shizuku 执行模块参考并改编自
[Soodok/Deepseek-Harness-Local-Android](https://github.com/Soodok/Deepseek-Harness-Local-Android)（MIT），
Shizuku API 来自 [RikkaApps/Shizuku-API](https://github.com/RikkaApps/Shizuku-API)。

## 免责声明

SHIZUKU / ROOT 模式下 Agent 可执行系统级命令，误操作可能损坏设备或数据。请仅在本人拥有或获得明确授权的设备上使用。默认 NORMAL 模式不提供 shell 通道。

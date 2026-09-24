# AzCode 开发进度与注意事项（交接文档）

> 更新时间：2026-09-24
> 当前暂缓 GenOffice 文档集成方向。本文汇总已完成任务、关键决策与踩坑记录，便于日后恢复开发。
> Android 端最新提交：`7e6460d`（v1.8.0 / versionCode 45，已推送 `260912-feat-android-native`）。

---

## 1. 项目概况

AzCode 是一个多端本地自动化 Agent：把自然语言任务交给大模型决策，再直接操作用户设备（读屏、键盘鼠标、shell 等）。应用显示名统一为 **AzCode**；GUI 形态打标签 **magic**、CLI 形态打标签 **Listen**（标签仅用于区分形态）。

| 端 | 分支 | 技术栈 | 说明 |
|----|------|--------|------|
| Windows | `260912-feat-windows-client` | .NET 8 / WPF | UI Automation + SendInput + PowerShell |
| Android | `260912-feat-android-native` | Kotlin / 原生 | 无障碍服务 + Shizuku/Root + Termux |
| Linux | `260912-feat-linux-native` | magic(GUI) / Listen(CLI) | 功能对等 |
| 主分支 | `main` | 文档 | 三端说明与命名规范 |

Git 仓库：`https://github.com/Azapp888/AzCode`

---

## 2. 已完成任务

### 2.1 Android 端（本阶段主线）

按版本倒序，均为已提交并推送的状态：

- **1.8.0 / 45**（`7e6460d`）桥接 GenOffice MCP：新增 `GenOfficeMcp`、`GenOfficeConfig`、「设置 → GenOffice」页、`genoffice_status` / `genoffice_document` 工具、文档卡片与逐页预览。详见第 3 节。
- **1.7.0 / 44**（`8fd8264`）LaTeX 兼容层：`normalizeLatex` 整段扫描，统一 `\(...\)` / `\[...\]` / `$...$` / `$$...$$`；新增 `sanitizeMath`（去掉 `\\[2ex]`、`\normalsize` 等 jlatexmath 不支持写法）；问答面板标题包进 `MaxHeightScrollView` 可滚动。
- **1.6.9 / 43**（`1b53d75`）思考深度白色圆球加大（`thumbRadius 18.5dp`），并用自定义玻璃卡片 `Dialog` 替换安卓原生弹窗（`dialog_depth_confirm.xml`）。
- **1.6.8 / 42**（`adf2cb3`）历史压缩一次性折叠为单条摘要，避免反复触发。
- **1.6.7 / 41**（`72553e1`）侧边栏美化。
- **1.6.6 / 40**（`e357edc`）消除切换会话卡顿（批量渲染 + 挂起布局）。
- **1.6.5 / 39**（`c4633d5`）修复闪退并补齐运行日志（全局 `CrashLog` + 关键路径 `runCatching` + `canShowUi()`）。
- **1.6.3 / 37**（`cccc470`）全应用统一 LaTeX 公式渲染（所有文本面）。
- **1.6.0**（`07b34da`）修复行内公式不渲染，技能支持自定义。
- **1.5.0 及更早**：接入 LaTeX（`ext-latex` + `jlatexmath-android`）、LLM 适配器重构（按厂商翻译请求）、豆包液态玻璃改版。

核心架构约定（Android）：
- 适配器：`BaseAdapter` 暴露 `translateRequest` / `translateResponse`（含 `translateStreamChunk`）；`LLMService` 按 `model` 路由到适配器。
- 思考参数按厂商翻译：DeepSeek → `think_effort` + `reasoning_effort`；豆包 → `reasoning`；网关拒绝时去参重试。
- 系统提示词做前缀缓存：稳定前缀 + 运行时上下文（`PromptCache`），工具集合顺序固定、不随条件增删。

### 2.2 Windows 端

- `ea9cf84` 补充 GitHub 接入说明与工具清单。
- `a9acb46` 接入用户 GitHub 仓库读写与 Issue/PR。
- `8f45103` 内置 impeccable 界面打磨技能并标注引用来源。
- `d3d05da` 应用名保持 AzCode，magic/Listen 作为形态标签。
- `ab4f64b` 对齐降缓存未命中机制、插件市场与内置 ponytail。

### 2.3 Linux 端

- `25d829e` 接入用户 GitHub 仓库读写与 Issue/PR。
- `a0ca9a7` 内置 impeccable 界面打磨技能。
- `0582135` 应用名统一为 AzCode。
- `17acd87` magic GUI / Listen CLI 双形态 Linux 客户端。

### 2.4 主分支

- `5b3c762` / `1b88b7e` 文档：三端说明与 magic/Listen 命名规范。

---

## 3. GenOffice 桥接集成（v1.8.0，本次新增，暂缓）

### 3.1 目标与路线

目标：让 Android 端能产出**真格式文档**（`.docx` / `.xlsx` / `.pptx`）并在 App 内预览。

路线（已与用户确认）：**不移植 GenOffice 的 TypeScript/Electron 引擎**，而是让 Android 端作为 MCP 客户端，桥接在 PC/Termux 上运行的 `genoffice mcp --http` 服务。

- GenOffice 仓库：`genspark-ai/genoffice`（Apache-2.0），双层架构「AI 出 Markdown/HTML → 引擎映射真格式」。
- 中文 fork：`yangshun2005/HermesOffice-cn`。
- Electron / Chromium / Node / Rust 均无法直接跑在 Android 上，故只能桥接。

### 3.2 协议与数据流

- 传输：MCP Streamable HTTP / JSON-RPC 2.0。
  - `POST /mcp`：握手 `initialize`（响应头返回 `Mcp-Session-Id`）→ `notifications/initialized` → `tools/list` / `tools/call`。
  - MCP SDK `^1.30.0`，协议版本用 `2025-06-18`。
  - **重要**：服务端默认以 SSE（`text/event-stream`）返回 POST 响应，客户端必须按 SSE 逐行读 `data:` 取目标 `id`，读到流末尾会超时。
- 文件传输：`PUT /files/<name>` 上传、`GET /files/<id>/<name>` 下载、`GET /health` 健康检查。
- 生成流程：`create_docx`/`create_xlsx`/`create_pptx`（**不传 `out`**，服务端写入会话 scratch 并返回 `output_url`）→ App 下载落盘 → 调 `render`（`file` 传 `output_url`，服务端 `resolveOwnUrl` 能映射回本地路径）取逐页 PNG。
- App 内预览采用**服务端 `render` 逐页 PNG**，避免依赖任何在线 Office 查看器。

### 3.3 配置与使用步骤

1. 在电脑安装 GenOffice，运行：`genoffice mcp --http 8765`（跨设备加 `--host 0.0.0.0`，建议加 `--token <密钥>`）。
2. 手机与电脑同一局域网；若在 Termux 中跑则地址填 `127.0.0.1`。
3. App「设置 → GenOffice」开启开关，填服务地址（可带或不带 `/mcp`，会自动规范化）与可选令牌。
4. 对 Agent 说「生成一份 Word/Excel/PPT …」即可；生成后聊天中显示文档卡片 + 逐页预览，可打开或分享原始文件。

### 3.4 涉及文件（Android）

- 新增 `app/src/main/java/app/azcode/bridge/GenOfficeMcp.kt`：`GenOfficeConfig`（开关/地址/令牌）+ MCP 客户端与高层能力（`status`、`createDocument`、`renderPreview`）。
- 新增 `app/src/main/java/app/azcode/bridge/GenOfficeActivity.kt` 与 `res/layout/activity_genoffice.xml`：设置页。
- 新增 `res/layout/item_msg_document.xml`：聊天内文档卡片。
- 修改 `AgentRunner.kt`：新增 `AgentEvent.Documents`、`genoffice_status` / `genoffice_document` 两个工具（放数组末尾以稳定前缀缓存）。
- 修改 `MainActivity.kt`：处理 `Documents` 事件、`addDocument` / `openDocument` / `shareDocument`（经 FileProvider）。
- 修改 `PromptCache.kt`：补充文档生成能力说明（含未配置时的引导）。
- 修改 `res/xml/file_paths.xml`：新增 `files-path genoffice` 供 FileProvider 分享。
- 修改 `AndroidManifest.xml`：注册 `GenOfficeActivity`。
- 版本：`app/build.gradle.kts` → versionCode 45 / versionName 1.8.0。

### 3.5 尚未验证（重要）

- **端到端未联调**：无 Android 设备（`adb devices` 为空）、也无运行中的 GenOffice 服务，仅编译通过。
- MCP 握手、SSE 解析、`create_*` 与 `render` 的真实返回结构，需要在真机 + 电脑侧服务下验证。
- xlsx 的 `data` 与 pptx 的 `spec` 参数格式需按 GenOffice guide 实际约定校对。

---

## 4. 注意事项与已知坑

### 4.1 环境与构建（Android）

- JDK17：`/usr/lib/jvm/java-17-openjdk-amd64`；Android SDK：`/opt/android-sdk`（build-tools 34.0.0）；Gradle 8.9：`/opt/gradle-8.9`。**无 kotlinc**。
- 构建命令：
  ```bash
  cd /tmp/opencode/azcode-android
  JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/opt/android-sdk \
  GRADLE_USER_HOME=/root/.gradle /opt/gradle-8.9/bin/gradle assembleDebug --no-daemon --console=plain
  ```
- 编译类命令须走受管后台终端（`background_terminal_create`），不要用自带 bash 直接跑 Gradle/Maven/Java。
- 构建告警（无害）：`MainActivity.confirmAction` redundant、`ProvidersActivity` ProgressDialog deprecated、`SettingsActivity` `versionCode` deprecated。
- APK 校验：`/opt/android-sdk/build-tools/34.0.0/aapt dump badging <apk>`，确认 `app.azcode.bridge`、版本号、`application-label:'AzCode'`。
- Android 工作树无 `gradle-wrapper.jar`，需用系统 gradle。

### 4.2 Android 编码约定

- `Switch` 一律用 `android.widget.Switch`（不要 SwitchCompat）。
- 生图参数必须按厂商翻译。
- 弹窗必须与整体设计一致，**不要用安卓底层默认弹窗**（已用自定义玻璃卡片 Dialog）。
- 用户输入/单行文本按纯文本处理，只替换公式，避免 `#` / `-` 被误判 markdown。
- LaTeX：行内 `$...$` 与块级 `$$...$$` 本地离线渲染；`normalizeLatex` 统一分隔符，`sanitizeMath` 兼容模型误写（`\\[2ex]`、`\normalsize` 等）。
- 历史压缩：全部旧历史一次性折叠为**单条**摘要，设最小增长量防抖，保护开头摘要。
- 常量：`AgentConfig.DEFAULT_MAX_STEPS=0`（无限步数）；`AgentRunner` 的 `COMPACT_THRESHOLD=40000`、`KEEP_USER_TURNS=4`、`COMPACT_MIN_GROWTH=20000`、`SUMMARY_INPUT_MAX=60000`。

### 4.3 设计令牌（Android 色值）

- 主色 `primary #3964FE`；`drawer_bg #FAFBFC`；`glass_surface #E6FFFFFF`；`surface #FFFFFF`；`background #F5F6F7`；`text_primary #0F1115`；`text_secondary #61666B`；`text_caption #ADB2B8`。

### 4.4 GenOffice 相关

- 服务端默认 SSE 响应，客户端读取方式见 3.2。
- 不带 `--token` 且非 loopback 绑定时服务端会告警「任何人可用」；生产建议加 token。
- 产物默认最大 256MB；内联资源仅在 ≤2MB 时随结果返回（否则用 `output_url` 下载）。
- 服务端 `render` / `convert-to-pdf` / `create_pdf` 会拉起隐藏 GenOffice 进程，耗时数秒。

### 4.5 其他

- GitHub Token、GenOffice 令牌等仅存应用私有存储，不读取环境变量、不硬编码。
- 提交署名统一：`Co-authored-by: monkeycode-ai <monkeycode-ai@chaitin.com>`。
- 不提交 `*.apk`；构建产物通过本地 HTTP 分发校验。
- `git push` 偶发 `gnutls_handshake()` 失败，重试可成功；GitHub API 有频率限制。

---

## 5. 后续待办（恢复开发时）

1. **GenOffice 端到端联调**：真机 + 电脑侧 `genoffice mcp --http` 服务，验证握手/SSE/生成/预览；校对 xlsx `data` 与 pptx `spec` 参数。
2. 在线预览增强：可选增量编辑工具（`docs_read` / `docs_apply`、`sheet_apply`、`slides_apply`）。
3. 停止按钮立即生效：让 `LLMTransport` 可取消。
4. 思考中卡死兜底。
5. 会话级并行：抽取 `SessionRuntime`。
6. 其他端（Windows/Linux）如需对等，再考虑各自接入 GenOffice。

---

## 6. 关键路径速查

- Android 源码：`/tmp/opencode/azcode-android/app/src/main/java/app/azcode/bridge/`
- 主循环/工具调度：`AgentRunner.kt`（工具 schema 的 `buildTools()`、工具分发 `execute()`）
- 配置：`AgentConfig.kt`（模型/思考深度/步数/提示词）、`GenOfficeMcp.kt`（GenOffice）、`GitHubClient.kt`（含 `GitHubConfig`）
- 界面：`MainActivity.kt`、`SettingsActivity.kt`、`ProvidersActivity.kt`、`SkillsActivity.kt`、`GenOfficeActivity.kt`
- LaTeX/Markdown：`Markdown.kt`、`MaxHeightScrollView.kt`
- 会话/日志：`SessionStore.kt`、`CrashLog.kt`

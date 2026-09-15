# AzCode (Android)

Android 原生端独立应用：在手机上输入自然语言任务，内置 DeepSeek 决策循环，直接调用本机无障碍 / Shizuku 能力操作手机。**不依赖其他端即可独立运行**。

应用名称仍为 **AzCode**。图形界面形态打标签 **magic**，命令行形态打标签 **Listen**——标签用于区分形态，不作为应用显示名。

同时内置一个仅绑定回环（`127.0.0.1:8848`）的 HTTP 能力桥，可经 `adb forward` 供 Listen 命令行或其他端复用同一套设备能力。

## Listen 命令行（Android）

电脑上装好 `adb`、连接手机并确保 AzCode App 前台运行（桥接服务开启）后，一行命令安装：

```bash
curl -fsSL https://raw.githubusercontent.com/Azapp888/AzCode/260912-feat-android-native/tools/install.sh | bash
```

使用：

```bash
listen run "打开设置查看 Android 版本号"   # 运行完整 Agent 任务
listen health                              # 能力自检
listen screen                              # 读取屏幕节点
listen tap --text "确定"                   # 按文本点击
listen shell "pm list packages -3"         # 执行命令（内置命令行，无需 Root/Shizuku）
listen notify --title "提醒" --body "内容"
```

原理：`listen` 通过 `adb forward tcp:8848 tcp:8848` 把 App 的本地桥暴露到电脑，再调用桥的 `/agent`、`/screen` 等接口。相关实现见 `tools/listen` 与 `tools/install.sh`。

## 独立运行（主要用法）

1. 打开无障碍服务：点右上角齿轮进入「设置」→「无障碍设置」→ 在系统设置里开启 **AzCode Screen Control**。
2. 在「设置」里填写 **DeepSeek API Key**（Base URL 与模型有默认值，可改），点「保存配置」。
3. 返回聊天界面，输入任务（如「打开设置查看 Android 版本号」）并发送。
4. shell 命令默认可直接用（内置命令行，应用自身权限）；需要系统级权限（访问其他应用私有目录、修改系统设置等）时，再安装 Shizuku 授权或切到 ROOT 模式。

### 界面

- **聊天式对话**：用户消息靠右（品牌蓝气泡、白字），助手回复靠左（白色卡片气泡）。
- **工具调用折叠框**：每一步实际执行的工具（读屏、点击、滑动、命令等）以可折叠卡片展示，默认收起；点击标题展开查看参数与结果，执行失败时自动展开。
- **思考指示器**：等待模型响应时显示「正在思考…」。
- **设置页**：模型配置、系统提示词、技能管理、无障碍/Shizuku/Root 状态与开关、桥接启停、权限模式切换全部收敛到设置页，主界面保持纯净。

### 外观与主题

- **视觉语言**：向 DeepSeek 官方 App 靠拢——灰色画布 + 白色卡片、品牌蓝点缀、大圆角、细描边、线性图标。色值与圆角取自 DeepSeek 官方设计系统，令牌清单见 `DESIGN.md`。
- **深色模式**：跟随系统自动切换，明/暗两套色值覆盖全部界面。
- **动态取色**：Android 12+ 启用 Material You，系统与 Material 控件跟随壁纸取色；品牌蓝以静态引用保持稳定。

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
- **内置技能**：随应用内置两个，可在技能页启停。
  - `ponytail · 拒绝过度设计`：最小改动原则，抑制多余依赖与抽象。
  - `impeccable · 界面打磨`：界面设计的工艺底线与 Android（Material 3）规范，移植自 [pbakaus/impeccable](https://github.com/pbakaus/impeccable)（Apache-2.0）。
- **从 GitHub 安装技能**：点技能页右上角「+」，填入 SKILL.md 的 GitHub 链接即可。支持：

```
https://raw.githubusercontent.com/owner/repo/main/path/SKILL.md
https://github.com/owner/repo/blob/main/path/SKILL.md
https://github.com/owner/repo/tree/main/subdir        （取该目录下 SKILL.md）
https://github.com/owner/repo                         （取默认分支根目录 SKILL.md）
owner/repo
```

安装时会解析 SKILL.md 的 YAML frontmatter（`name` / `description`），没有则取首个标题与首行正文。

### 插件市场

- **内置 ponytail**：首次启动写入「拒绝过度设计」技能（`ponytail · 拒绝过度设计`），默认启用，可随时停用。
- **扫描热门插件**：技能页「扫描热门插件」按钮按关键词搜索 GitHub 上 star 较多的 Agent 技能仓库，点选即可一键安装；可留空查看内置精选。
- **Agent 自助**：模型也可调用 `search_plugins` / `install_plugin` / `list_installed_plugins` / `set_plugin_enabled` / `remove_plugin` 完成同样的操作。

### GitHub 接入

在设置页 → 「GitHub」填写 Personal Access Token（需 `repo` scope），可选填默认仓库与分支，保存前会调用 GitHub API 校验 Token。Token 属于用户自有凭据，仅存于应用私有存储，不读取任何环境变量或平台内部变量。接入后 Agent 可以：

- 读取与提交仓库文件（`github_read_file` / `github_write_file`，写文件自动探测 sha）；
- 浏览仓库、分支与提交记录；
- 管理 Issue 与评论、查看与创建 Pull Request；
- 搜索公开仓库；
- 插件搜索与技能安装时自动复用该 Token，可安装私有仓库中的技能。

工具集中不包含删除仓库等不可逆操作。

Agent 工具集：

| 工具 | 说明 |
| --- | --- |
| `get_screen` | 读取当前屏幕节点（文本/坐标/可点击性） |
| `tap` | 坐标或按文本点击 |
| `swipe` | 滑动 |
| `global` | back / home / recents / notifications |
| `shell` | 执行命令：默认内置命令行（应用权限），可选 Shizuku/Root 提权 |
| `generate_image` | 文生图（需配置生图模型） |
| `ask_question_for_user` | 在输入框下方问答区向用户提问（支持一次多个问题） |
| `search_plugins` / `install_plugin` / `list_installed_plugins` / `set_plugin_enabled` / `remove_plugin` | 插件市场 |
| `github_status` / `github_save_config` | 查看与保存 GitHub 接入状态 |
| `github_list_repos` / `github_get_repo` / `github_list_branches` / `github_list_commits` | 浏览仓库、分支与提交 |
| `github_read_file` / `github_write_file` | 读取与提交仓库文件（写文件自动探测 sha） |
| `github_list_issues` / `github_create_issue` / `github_comment_issue` | 管理 Issue 与评论 |
| `github_list_pulls` / `github_create_pull` / `github_search_repos` | Pull Request 与仓库搜索 |
| `list_model_providers` / `fetch_models` / `save_model_provider` / `remove_model_provider` / `import_providers_md` | 模型配置 |
| `finish` | 结束任务并总结 |

### 降缓存未命中机制

为最大化大模型提供商的前缀缓存命中率（移植自 deepseek-harness）：

- **稳定前缀**：第 0 条 `system` 只放用户长期设置的人设，字节永久不变。
- **运行时上下文**：技能、记忆、思考深度、生图安排单独成一条 `system` 消息；内容未变时不重复插入，变化时**追加到历史末尾**，绝不重写前缀。
- **工具定义**顺序固定、不随条件增删（`generate_image` 始终声明，未配置生图模型时调用会返回友好错误）。

支持在任意位置追加 `system` 消息的模型（如 deepseek-flash）会把最新一条视为完整系统提示，旧的那条自然被取代，前缀保持不变。实现见 `PromptCache.kt`。

## 架构

```
Android (app.azcode.bridge)
  MainActivity ── AgentRunner ── LLMService ──▶ 厂商适配器 ──▶ 各厂商官方 API
                       │              │
                       │              └── llm/core      内部统一格式 LLMRequest / LLMResponse
                       │                  llm/adapters   DeepSeek / 豆包 / OpenAI / Claude / Gemini
                       │                  llm/LLMRegistry 厂商识别与适配器注册
                       │
                       ├── AzAccessibilityService  读屏 / 点击 / 滑动 / 全局动作
                       └── DeviceControl           Shizuku / Root shell 通道

  （可选）AgentBridge 127.0.0.1:8848 ── adb forward ──▶ Windows 端
```

### 大模型适配层（llm 包）

业务代码只与内部统一格式 `LLMRequest` / `LLMResponse` 打交道（含 `messages`、`tools`、`thinking`、`stream`、`usage` 等），由 `BaseAdapter` 的子类负责与厂商官方 API 之间的翻译：

- `translateRequest(request)`：内部统一请求 → 厂商请求体；
- `translateResponse(raw)`：厂商响应 → 内部统一响应；
- 厂商差异（如 DeepSeek 的 `think_effort`、豆包的 `reasoning`、Claude 的 system+blocks、Gemini 的 contents/parts）全部封装在适配器内；
- 文生图同样按厂商翻译：火山方舟用组图字段替代 `n` 并关闭水印、硅基流动用 `image_size` 逐张生成、OpenAI `gpt-image-*` 不带 `response_format`、`dall-e-3` 自动拆成多次请求；
- 网关不识别思考参数时，`LLMService` 自动去掉该参数重试一次；
- 网络错误统一为 `LLMException`（含中文提示与标准错误码）；生图连续失败 3 次会主动停止，避免长时间空转。

新增一个模型厂商只需两步：在 `llm/adapters` 下新建适配器类，再到 `LLMRegistry` 注册；基类与业务代码无需改动。



## 能力桥 API（可选，供 Windows 端调用）

| 方法 | 路径 | Body / 说明 |
| --- | --- | --- |
| GET | `/health` | 返回 accessibility / shizuku / root / mode 能力探测 |
| GET | `/screen` | 当前窗口可见节点（text/desc/cls/x/y/w/h/clickable），上限 200 |
| POST | `/tap` | `{"x":123,"y":456}` 或 `{"text":"确定"}` |
| POST | `/swipe` | `{"x1":..,"y1":..,"x2":..,"y2":..,"duration":300}` |
| POST | `/global` | `{"action":"back"\|"home"\|"recents"\|"notifications"}` |
| POST | `/shell` | `{"cmd":"pm list packages"}`，NORMAL 模式即内置命令行 |
| POST | `/notify` | `{"title":"..","body":".."}` |
| POST | `/agent` | `{"task":"..","session":"可选会话 id"}` 运行完整 Agent 任务，返回 `answer` 与 `events` |

## 命令执行模式

shell 命令默认可用，分两层：

1. **Termux（优先）**：设备已安装 Termux 并授权后，`shell` 自动通过 Termux 的 `RUN_COMMAND` 接口执行，等价于完整 Linux 环境，可跑 `python`、`node`、`git`、`pip`、`ffmpeg` 等 Termux 里已安装的软件包。
2. **内置命令行（兜底）**：未装 Termux 或 Termux 不可用时，以应用自身权限调用 `/system/bin/sh -c`，可直接跑 `sh`、`ls`、`cat`、`getprop`、`pm`、`am`、`dumpsys` 等命令。

两者都无需 Root/Shizuku。受沙箱限制，访问其他应用私有目录、修改系统设置等仍需 SHIZUKU/ROOT。

### 启用 Termux（可选）

Termux 是独立应用，前缀（`$PREFIX`）数百 MB，无法塞进本 APK，因此按需接入：

1. 安装 Termux（`com.termux`，F-Droid 或 GitHub 版）；
2. 在「设置 → 设备能力 → Termux 授权」授予 `com.termux.permission.RUN_COMMAND`；
3. 在 Termux 里执行以下命令并重启 Termux，开启外部应用调用：

    ```bash
    echo "allow-external-apps=true" >> ~/.termux/termux.properties
    ```

之后 `shell` 会自动优先走 Termux；若调用失败则静默回退内置命令行，不会中断任务。

| 模式 | `/shell` 通道 | 前置条件 |
| --- | --- | --- |
| NORMAL（Termux） | Termux `RUN_COMMAND`，以 Termux uid 执行 bash | 安装 Termux + 授权 + `allow-external-apps=true` |
| NORMAL（内置命令行） | `/system/bin/sh -c`，以应用 uid 执行 | 无 |
| SHIZUKU | Shizuku binder，以 adb(uid 2000) 身份执行；不可用时回退 NORMAL | 安装并启动 Shizuku，应用内授权 |
| ROOT | `su -c`，以 uid 0 执行；不可用时回退 NORMAL | 设备已 Root |

内置命令行超时 60 秒，Termux 超时 90 秒，超时自动终止，避免交互式命令挂死 Agent。

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
  GitHubActivity.kt          GitHub 接入页：Token / 默认仓库 / 默认分支
  SkillsActivity.kt          技能管理：启停、删除、从 GitHub 安装、扫描热门插件
  AgentRunner.kt             设备端 Agent 决策循环，向 UI 输出结构化事件
  PromptCache.kt             降缓存未命中：稳定前缀 + 运行时上下文追加 + 工具顺序固定
  DeepSeekClient.kt          DeepSeek function calling + 多模态内容构造
  AgentConfig.kt             多提供商 / 模型 / 最大步数 / 系统提示词持久化
  SkillStore.kt              技能持久化 + 内置 ponytail / impeccable
  PluginCatalog.kt           热门插件扫描（GitHub 搜索）与一键安装
  GitHubClient.kt            GitHub REST 客户端与 Token 私有存储
  GitHubSkillFetcher.kt      GitHub SKILL.md 下载与解析
  AttachmentReader.kt        附件读取：图片/PDF/Office/文本
  AzAccessibilityService.kt  读屏 / 点击 / 滑动 / 全局动作
  DeviceControl.kt           权限模式 + Shizuku/Root shell 执行
  AgentBridge.kt             127.0.0.1:8848 环回 HTTP 能力桥（含 /agent 完整任务）
  BridgeService.kt           specialUse 前台服务保活桥接
app/src/main/res/layout/     聊天页 / 设置页 / 技能页 / 各类消息卡片
app/src/main/res/xml/azcode_accessibility_service.xml
tools/listen                 Listen 命令行（经 adb forward 调用桥）
tools/install.sh             Listen 一行安装脚本
.github/workflows/android.yml
```

## 来源与许可

MIT。无障碍与 Shizuku 执行模块参考并改编自
[Soodok/Deepseek-Harness-Local-Android](https://github.com/Soodok/Deepseek-Harness-Local-Android)（MIT），
Shizuku API 来自 [RikkaApps/Shizuku-API](https://github.com/RikkaApps/Shizuku-API)。

界面设计参考 DeepSeek 官方样式（令牌来源见 `DESIGN.md` 的「参考来源与引用文件」）；
内置 `impeccable` 技能移植自 [pbakaus/impeccable](https://github.com/pbakaus/impeccable)（Apache-2.0）。

## 免责声明

SHIZUKU / ROOT 模式下 Agent 可执行系统级命令，误操作可能损坏设备或数据。请仅在本人拥有或获得明确授权的设备上使用。默认 NORMAL 模式不提供 shell 通道。

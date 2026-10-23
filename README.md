# AzCode (Android)

AzCode 是运行在 Android 手机本地的自动化助手。用自然语言描述任务，应用内置 Agent 决策循环，通过无障碍服务读取屏幕、点击控件、滑动与执行命令，直到任务完成。

- 应用名：**AzCode**
- 图形界面形态标签：**magic**
- 命令行形态标签：**Listen**
- 当前版本：**1.9.15**（versionCode 70）

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
| 语音输入 | 系统离线识别与讯飞在线识别可切换，支持按住说话、常用命令推荐 |
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

从 GitHub Releases 下载 `android-v*` 版本的 APK 安装。debug 与 release 使用仓库内同一把签名密钥，可以互相覆盖安装。

### 首次配置

1. 打开「设置 → 设备能力 → 无障碍设置」，在系统设置中开启 **AzCode Screen Control**。
2. 进入「设置 → 模型管理」，添加并启用一个模型提供商，填写 API Key；Base URL 与模型可选用预设模板。
3. 返回主界面，在模型选择器中确认当前模型。
4. 输入任务并发送，例如「打开设置查看 Android 版本号」。

按需授予以下权限：

| 权限 | 用途 | 入口 |
| --- | --- | --- |
| 无障碍服务 | 读屏、点击、滑动、系统导航、文本写入 | 设置 → 设备能力 |
| 麦克风 | 语音输入与悬浮助理 | 首次使用时申请 |
| 通知 | 任务完成提醒与提问通知 | 首次发送任务时申请 |
| 显示在其他应用上层 | 悬浮助理光晕 | 设置 → 悬浮光晕权限 |
| 相机 | 拍照附件 | 首次拍照时申请 |
| Termux RUN_COMMAND | 通过 Termux 执行完整 Linux 命令 | 设置 → 设备能力 → Termux |
| Shizuku | 以 adb 身份执行系统级命令 | 设置 → 设备能力 → Shizuku |

### 从源码构建

本地需要 JDK 17 与 Android SDK。仓库不包含 `gradle-wrapper.jar`，使用系统 `gradle`：

```bash
gradle assembleDebug
gradle assembleRelease
```

推送代码后，`.github/workflows/android.yml` 会在 GitHub Actions 上构建 Debug 与 Release APK 并上传产物。推送 `android-v*` 标签时会创建 GitHub Release。

## 交互界面

### 主聊天页

- 用户消息靠右，使用品牌蓝气泡与白字；助手回复靠左，使用白色卡片气泡。
- 工具调用以可折叠卡片展示，默认收起；失败时自动展开。卡片包含工具名、参数、执行状态与结果。
- 等待模型响应时显示「正在思考…」，可随时点击停止。
- 欢迎页提供示例任务，首次进入时展示。
- 底部输入区包含附件、模型选择、思考深度、语音入口与发送/停止按钮。

### 会话历史

- 左上角菜单打开历史抽屉，按今天、昨天、7 天内、更早分组。
- 支持新建任务、切换任务、删除任务。
- 首条用户消息自动生成会话标题，最多保留 20 个字符。
- 会话保留聊天展示记录与 Agent 上下文，切换后可以继续追问。

### 模型与思考深度

- 模型选择器按提供商分组，列出所有已启用账号下的语言模型。
- 选中某个模型后写回该提供商的当前模型。
- 思考深度分为关闭、快速、标准、深度，映射到 `reasoning_effort` 或厂商等价参数。
- 拉满深度前会弹出费用提示，确认后生效。

### 附件

输入框左侧回形针提供拍照、相册、文件三种入口。附件在发送前完成本地读取与转码：

| 类型 | 扩展名 | 处理方式 | 限制 |
| --- | --- | --- | --- |
| 图片 | jpg / jpeg / png / gif / webp | Base64 内联，走视觉输入 | 原文件超过 4 MB 时压缩，最长边不超过 8192 |
| 音频 | mp3 / wav / m4a / aac / ogg / flac / amr / opus / 3gp | Base64 内联，交给支持音频的模型 | 最大 12 MB |
| PDF | pdf | `PdfRenderer` 渲染为 JPEG 后作为图片发送 | 最多 8 页 |
| Office | docx / xlsx / pptx | 本地解包 XML 提取文本并并入消息 | 最多 200000 字符 |
| 文本与代码 | txt / md / csv / json / xml / yaml / 多种源码 | 直接读取为文本 | 最多 200000 字符 |

旧版二进制 Office（.doc / .xls / .ppt）需要另存为 OOXML 格式。图片与音频输入需要模型本身支持对应模态；音频被模型拒绝时会弹窗提示更换模型。

附件栏显示图片缩略图与文件类型图标，点击可移除。

### 语音输入

长按输入框进入语音面板，松开后自动发送：

- 语音面板显示实时波动条与识别预览。
- 左下角推荐命令来自本地命令记忆，按使用频次与最近使用时间排序；样本不足时使用内置示例补齐。
- 右下角可切换到键盘输入。
- 语音引擎可选系统内置（离线优先）或讯飞在线识别；讯飞凭据由 CI Secrets 覆盖，未配置时使用仓库内置凭据。
- 当前设备不支持语音识别时给出提示，可改用键盘输入。

### 悬浮助理与系统助理入口

在「设置 → 设为数字智能助理」中把 AzCode 注册为系统默认助理。之后通过长按电源键、耳机按键或助理手势唤醒：

1. 透明中转页检查悬浮窗与麦克风权限。
2. 启动前台服务，在当前页面之上绘制透明背景的边缘呼吸蓝光。
3. 左下角悬浮气泡显示识别文本与执行进度，右下角提供键盘输入，右上角提供关闭按钮。
4. 说完停顿后自动提交；任务在当前页面直接执行，不跳回 AzCode 主界面。
5. 需要用户作答时，问题面板直接显示在当前页面，同时发送通知提醒。

无障碍服务在执行点击、滑动等操作时也会显示「AI 正在操作手机」光效；应用自身界面位于前台时左下角气泡隐藏。

### 提问与通知

- Agent 可通过 `ask_question_for_user` 在输入框下方展开问答区，支持单选、多选与手动输入。
- 一次可以提交多个问题，界面逐条询问并显示进度。
- 提问同时发送高优先级通知，展开通知即可点选选项或输入答案。
- 任务完成、停止或失败时发送任务提醒。

### 图片与文档结果

- 生成的图片在聊天中直接展示，点击可放大并保存到相册 `Pictures/AzCode`。
- 生成的 Office 文档以卡片展示，包含逐页预览图、打开与分享按钮。

## 设置页导航

| 分区 | 入口 | 功能 |
| --- | --- | --- |
| 模型 | 模型管理 | 添加、编辑、启停、删除提供商；配置语言模型与生图模型 |
| 账户 | GitHub 接入 | 保存并验证 Personal Access Token、默认仓库与分支 |
| 账户 | GenOffice 文档 | 配置 MCP 服务地址与访问令牌，测试连接 |
| 应用 | 技能管理 | 启用、停用、编辑、删除技能；从 GitHub 安装；扫描热门插件 |
| 应用 | 记忆 | 分条管理敏感信息、使用习惯、技能方法 |
| 应用 | 运行日志 | 查看、复制、清空日志 |
| 应用 | 输入法 | 启用 AzCode 输入法 |
| 应用 | 个性化与隐私 | 查看授权说明、生成本机快照、撤销授权 |
| 语音 | 语音输入 | 选择系统内置或讯飞引擎 |
| 助理 | 设为数字智能助理 | 打开系统默认助理设置页 |
| 助理 | 悬浮光晕权限 | 打开「显示在其他应用上层」授权页 |
| 运行 | 最大步数 | 0 表示无限步数 |
| 运行 | 系统提示词 | 留空使用内置默认提示词 |
| 运行 | 生成图片水印 | 仅对支持水印参数的厂商生效 |
| 设备能力 | 无障碍设置 / Shizuku / Termux / 桥接 / 权限模式 | 检查状态并授权 |
| 关于 | 版本 | 显示当前版本与版本号 |

## 模型接入

### 协议

| 协议 | 说明 | 文生图 |
| --- | --- | --- |
| OpenAI 兼容 | 覆盖 DeepSeek、火山方舟、硅基流动、OpenAI 及大多数兼容网关 | 支持 |
| Anthropic Messages | Claude 官方消息格式 | 不支持 |
| Google Gemini | `generateContent` 格式 | 不支持 |

### 厂商适配器

业务代码只接触内部统一的 `LLMRequest` / `LLMResponse`，厂商差异由 `llm/adapters` 翻译：

| 厂商 | 适配器 | 识别依据 |
| --- | --- | --- |
| DeepSeek | `DeepSeekAdapter` | 地址或模型名包含 deepseek |
| 火山方舟（豆包） | `DoubaoAdapter` | 地址包含 volces / ark，或 doubao / seed 模型 |
| 硅基流动 | `SiliconFlowAdapter` | 地址包含 siliconflow，或 Kolors / Qwen-Image 模型 |
| OpenAI | `OpenAiAdapter` | 地址包含 openai.com，或 gpt / o1 / o3 / o4 模型 |
| Anthropic Claude | `AnthropicAdapter` | Anthropic 协议 |
| Google Gemini | `GeminiAdapter` | Gemini 协议 |
| 其他兼容平台 | `OpenAiCompatAdapter` | OpenAI 兼容协议且未命中以上规则 |

新增厂商只需在 `llm/adapters` 下实现适配器并在 `LLMRegistry` 注册，业务代码无需改动。

### 内置平台预设

| 平台 | Base URL | 语言模型示例 | 生图模型示例 |
| --- | --- | --- | --- |
| DeepSeek | `https://api.deepseek.com/v1` | deepseek-chat / deepseek-reasoner | 无 |
| 火山方舟（豆包） | `https://ark.cn-beijing.volces.com/api/v3` | doubao-seed-1-6-250615 | doubao-seedream-4-0-250828 |
| 硅基流动 | `https://api.siliconflow.cn/v1` | deepseek-ai/DeepSeek-V3 | Kwai-Kolors/Kolors |
| OpenAI | `https://api.openai.com/v1` | gpt-4o / gpt-4o-mini | gpt-image-1 / dall-e-3 |
| Anthropic Claude | `https://api.anthropic.com/v1` | claude-3-5-sonnet-latest | 无 |
| Google Gemini | `https://generativelanguage.googleapis.com/v1beta` | gemini-2.0-flash | 无 |

### 文生图

- 生图统一调用 OpenAI 风格的 `POST {Base URL}/images/generations`。
- 火山方舟使用组图字段，支持水印开关；硅基流动使用 `image_size` 并逐张生成。
- OpenAI `gpt-image-*` 返回 Base64，`dall-e-3` 单次只生成 1 张，适配器会自动拆分请求。
- 未配置生图模型时，`generate_image` 仍会出现在工具列表中并返回可读提示，避免工具 schema 变化破坏缓存。
- 连续失败 3 次自动停止，防止任务空转。

### 提示词与上下文

- 第 0 条 system 消息保持稳定：用户人设与内置能力手册按固定字节拼接。
- 技能、记忆、思考深度、生图安排放在单独的运行时 system 消息中；内容不变时不重复插入，变化时追加到历史末尾。
- 工具声明顺序固定，不随条件增删。
- 上下文估算超过 40000 字符时触发摘要压缩，保留最近 4 个用户轮次；再次压缩需额外累积 20000 字符。
- 每个会话最多持久化 40 条 Agent 消息，压缩摘要始终保留。

## Agent 工具

### 设备与界面

| 工具 | 说明 |
| --- | --- |
| `get_screen` | 读取当前屏幕可见节点，包含文本、坐标、是否可点击 |
| `tap` | 按文本或坐标点击 |
| `swipe` | 按起点、终点与持续时间滑动 |
| `global` | back / home / recents / notifications |
| `input_text` | 向当前焦点输入框写入 Unicode 文本，可追加 |
| `shell` | 按当前权限模式执行命令 |
| `list_apps` | 列出可启动应用，支持关键词过滤 |
| `open_app` | 按应用名或包名启动应用 |
| `finish` | 结束任务并给出总结 |

### 模型与生成

| 工具 | 说明 |
| --- | --- |
| `list_model_providers` | 查看提供商、模型与启用状态 |
| `fetch_models` | 从 `/models` 拉取可用模型 |
| `save_model_provider` | 新增或更新提供商 |
| `remove_model_provider` | 删除提供商 |
| `import_providers_md` | 从 Markdown 批量导入提供商 |
| `generate_image` | 根据描述生成图片 |
| `genoffice_status` | 检查 GenOffice 服务 |
| `genoffice_document` | 生成 docx / xlsx / pptx |

### 记忆与技能

| 工具 | 说明 |
| --- | --- |
| `save_memory` | 写入长期记忆，分类为 token / habit / method |
| `list_memories` | 查看记忆，可按分类过滤 |
| `remove_memory` | 删除记忆 |
| `set_memory_enabled` | 启用或停用记忆 |
| `save_skill` | 把流程保存为技能 |
| `search_plugins` | 扫描热门开源插件 |
| `install_plugin` | 安装插件 |
| `list_installed_plugins` | 列出已安装插件 |
| `set_plugin_enabled` | 启用或停用插件 |
| `remove_plugin` | 删除插件 |

### GitHub

| 工具 | 说明 |
| --- | --- |
| `github_status` | 查看接入状态 |
| `github_save_config` | 保存 Token、默认仓库与分支 |
| `github_list_repos` | 列出可访问仓库 |
| `github_get_repo` | 查看仓库概览 |
| `github_list_branches` | 列出分支 |
| `github_read_file` | 读取文件与 sha |
| `github_write_file` | 创建或更新文件并提交 |
| `github_list_commits` | 查看提交记录 |
| `github_list_issues` / `github_create_issue` / `github_comment_issue` | 管理 Issue 与评论 |
| `github_list_pulls` / `github_create_pull` | 查看与创建 Pull Request |
| `github_search_repos` | 搜索公开仓库 |

### 交互

| 工具 | 说明 |
| --- | --- |
| `ask_question_for_user` | 在页面内或通知栏向用户提问，支持多问题、多选与自定义输入 |

工具集中不包含删除仓库等不可逆操作。

## 设备能力与命令执行

shell 命令始终可用，按权限模式选择通道：

| 模式 | 通道 | 身份 | 前置条件 | 超时 |
| --- | --- | --- | --- | --- |
| NORMAL + Termux | Termux `RUN_COMMAND` | Termux uid | 安装 Termux、授予 RUN_COMMAND、设置 `allow-external-apps=true` | 90 秒 |
| NORMAL + 内置 shell | `/system/bin/sh -c` | 应用 uid | 无 | 60 秒 |
| SHIZUKU | Shizuku binder | adb uid 2000 | 启动 Shizuku 并授权 | 60 秒 |
| ROOT | `su -c` | uid 0 | 设备已 Root | 60 秒 |

NORMAL 模式会优先尝试 Termux，失败或未就绪时回退内置 shell。Shizuku 与 Root 不可用时同样回退 NORMAL。访问其他应用私有目录、修改系统设置等系统级操作需要 Shizuku 或 Root。

读屏、点击、滑动、系统导航与 `input_text` 依赖无障碍服务。`input_text` 优先使用无障碍 `ACTION_SET_TEXT`，不可用时回退到内置 AzCode 输入法。

### 应用启动策略

`open_app` 按以下顺序尝试：

1. 使用 shell 查询包名并启动：`monkey` 或 `am start`。
2. 回到桌面，读取屏幕节点，按应用名点击；抽屉式桌面会先滑动或打开全部应用。
3. 仍失败时通过 `ask_question_for_user` 请用户确认应用名称、安装状态，或请用户手动打开。

## 本地数据与存储

AzCode 没有 SQLite、Room 或服务端数据库。数据层由应用私有 `SharedPreferences` 与 JSON 文件组成，全部位于应用沙箱内。

### SharedPreferences

| 文件 | 键 | 内容 |
| --- | --- | --- |
| `azcode_agent` | `providers_json` | 提供商账号数组，含协议、地址、Key、语言模型、生图模型与启用状态 |
| `azcode_agent` | `active_provider` | 当前使用的提供商 id |
| `azcode_agent` | `thinking_depth` | off / fast / standard / deep |
| `azcode_agent` | `max_steps` | 最大步数，0 表示无限 |
| `azcode_agent` | `system_prompt` | 用户自定义系统提示词 |
| `azcode_agent` | `image_watermark` | 文生图水印开关 |
| `azcode_sessions` | `current_id` | 当前会话 id |
| `azcode_skills` | `skills` | 技能数组，含 id、名称、描述、正文、来源与启用状态 |
| `azcode_command_memory` | `commands` | 命令到 `{count,last}` 的映射；最多 60 条，单条最长 80 字符 |
| `azcode_github` | `token` / `default_repo` / `default_branch` / `login` | GitHub 接入配置与验证后的登录名 |
| `azcode_genoffice` | `enabled` / `url` / `token` | GenOffice MCP 接入配置 |
| `azcode_personalization` | `consented` / `consented_at` / `snapshot_at` / `prompt_shown` | 个性化授权与快照状态 |
| `azcode_speech` | `engine` | system / xunfei |
| `azcode_priv` | `priv_mode` | NORMAL / SHIZUKU / ROOT |

旧版单账号字段 `api_key`、`base_url`、`model`、`image_model` 仅在首次读取时用于迁移到 `providers_json`。

### 文件

| 路径 | 格式 | 内容 |
| --- | --- | --- |
| `files/sessions/<会话 id>.json` | JSON 对象 | 单个会话的标题、更新时间、聊天记录与 Agent 上下文 |
| `files/memory.json` | JSON 数组 | 全部记忆条目 |
| `files/logs/YYYY-MM-DD.log` | 文本 | 按天轮转的运行日志，最多 10 个文件，单文件上限 512 KB |
| `files/genoffice/` | 文件 | 生成的 Office 文档 |
| `cache/az_images/` | 文件 | 图片展示缓存 |
| `cache/captures/` | 文件 | 拍照产生的临时文件 |

会话 JSON 结构：

```json
{
  "id": "uuid",
  "title": "会话标题",
  "updatedAt": 1700000000000,
  "turns": [
    {
      "kind": "user",
      "text": "任务内容",
      "attachments": ["文件名"],
      "toolName": "",
      "toolArgs": "",
      "toolOk": false,
      "toolResult": "",
      "images": []
    }
  ],
  "agentMessages": "[]"
}
```

`turns` 面向界面展示，`kind` 取值为 user / assistant / notice / error / tool / image。`agentMessages` 保存续接给模型的消息数组，图片等大体积内容在持久化前会被剥离。

记忆 JSON 结构：

```json
{
  "id": "uuid",
  "category": "token",
  "title": "标题",
  "content": "内容",
  "sensitive": true,
  "enabled": true,
  "createdAt": 1700000000000
}
```

记忆分类：

| 分类 | 说明 | 是否注入模型 |
| --- | --- | --- |
| `token` | API Key、密码等敏感信息，界面默认打码 | 启用时注入 |
| `habit` | 常用应用、语言、称呼、输出偏好等使用习惯 | 启用时注入 |
| `method` | 用户教过的命令、工具调用方式与流程 | 启用时注入 |
| `local` | 已安装应用、权限声明、设备型号与系统版本 | 仅保存在本地，不注入 |

### 写入与保留策略

- 会话写盘采用临时文件加 rename，避免并发写入产生半个 JSON 文件。
- 会话对象按最近访问保留 6 个内存缓存；历史列表使用摘要缓存，切换会话时无需重复解析全部文件。
- Agent 上下文最多保留 40 条消息；更早内容通过摘要压缩保留。
- 日志按天轮转，超过上限自动裁剪。
- 卸载应用会删除全部本地数据。

### 清除数据

| 数据 | 清除方式 |
| --- | --- |
| 单个会话 | 历史抽屉或任务历史页删除 |
| 单条记忆 | 记忆页删除 |
| 本机个性化数据 | 设置 → 个性化与隐私 → 撤销授权并清除本地数据 |
| 运行日志 | 设置 → 运行日志 → 清空日志 |
| GitHub 配置 | 设置 → GitHub 接入 → 断开连接 |
| 全部数据 | 系统设置中清除应用数据或卸载应用 |

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

### 安装 Listen

电脑需安装 `adb`、连接手机，并让 AzCode 在前台运行且开启桥接：

```bash
curl -fsSL https://raw.githubusercontent.com/Azapp888/AzCode/main/tools/install.sh | bash
```

### 使用 Listen

```bash
listen run "打开设置查看 Android 版本号"
listen health
listen screen
listen tap --text "确定"
listen shell "pm list packages -3"
listen notify --title "提醒" --body "内容"
```

实现见 `tools/listen` 与 `tools/install.sh`。

## 构建、签名与发布

### 版本信息

| 项目 | 值 |
| --- | --- |
| applicationId | `app.azcode.bridge` |
| compileSdk / targetSdk | 34 |
| minSdk | 26 |
| versionCode | 70 |
| versionName | 1.9.15 |
| JDK | 17 |

版本号位于 `app/build.gradle.kts`。

### 签名

`keystore/azcode-release.keystore` 为公开的自用签名密钥，debug 与 release 共用同一把密钥，保证产物签名一致、可覆盖安装。密钥说明见 `keystore/README.md`。

### CI

`.github/workflows/android.yml` 在以下场景触发：

- 推送到 `main` 或 `260912-feat-android-native` 分支
- 推送 `android-v*` 标签
- 手动触发

CI 会注入讯飞语音 Secrets（`XUNFEI_APP_ID`、`XUNFEI_API_KEY`、`XUNFEI_API_SECRET`），构建 Debug 与 Release APK，校验签名并上传产物。标签构建会额外创建 GitHub Release。

## 目录结构

```text
app/src/main/java/app/azcode/bridge/
  MainActivity.kt             主聊天页：会话、消息、模型选择、附件、语音
  AgentRunner.kt              Agent 决策循环、工具执行、上下文压缩
  PromptCache.kt              稳定前缀、运行时上下文、能力手册
  AgentConfig.kt              提供商、模型、思考深度、步数、提示词
  ModelProviders.kt           提供商模型与平台预设
  SessionStore.kt             会话持久化、摘要缓存、原子写盘
  MemoryStore.kt              记忆持久化、分类与打码
  SkillStore.kt               技能持久化与内置技能
  CommandMemory.kt            常用命令记忆
  PluginCatalog.kt            热门插件扫描与安装
  GitHubClient.kt             GitHub REST 客户端与配置
  GitHubSkillFetcher.kt       GitHub SKILL.md 解析与安装
  GenOfficeMcp.kt             GenOffice 配置与 MCP 客户端
  AttachmentReader.kt         图片、音频、PDF、Office、文本读取
  AzAccessibilityService.kt   读屏、点击、滑动、导航、光效
  DeviceControl.kt            权限模式与命令执行
  TermuxControl.kt            Termux RUN_COMMAND 通道
  AgentBridge.kt              127.0.0.1:8848 能力桥
  BridgeService.kt            桥接前台服务
  AssistActivity.kt           系统助理透明入口
  AssistOverlayService.kt     悬浮助理光晕、语音与页面内提问
  SpeechEngine.kt             语音引擎接口
  SystemSpeechEngine.kt       系统语音识别
  XunfeiSpeechEngine.kt       讯飞在线语音识别
  TaskNotifier.kt             任务提醒与提问通知
  CrashLog.kt                 崩溃与运行日志
  Personalization.kt          本机个性化快照与授权
  ime/                        内置 AzCode 输入法
  llm/                        统一请求响应、传输、注册表与厂商适配器
app/src/main/res/layout/      聊天页、设置页、消息卡片、助理悬浮层等
tools/listen                  Listen 命令行
tools/install.sh              Listen 安装脚本
.github/workflows/android.yml Android 构建与发布流程
keystore/                     统一签名密钥
```

## 隐私与安全

- 模型 API Key、GitHub Token、GenOffice 令牌与讯飞凭据仅保存在应用私有存储中，应用不读取环境变量。
- 本机个性化快照只在设备本地生成，不注入云端对话上下文。
- 能力桥仅绑定环回地址，跨机访问依赖 adb 转发。
- 讯飞在线识别会把语音发送到讯飞开放平台；系统内置识别优先使用设备端离线能力。
- GenOffice 会把文档内容发送到用户配置的局域网 MCP 服务。
- Shizuku 与 Root 模式可以执行系统级命令，误操作可能损坏设备或数据。请只在本人拥有或获得明确授权的设备上使用。
- 旧版二进制 Office 文件不受支持，请另存为 OOXML 格式。

## 已知限制

- 读屏与界面操作依赖无障碍服务；部分厂商系统会限制后台无障碍服务存活。
- 系统级 shell 能力依赖 Shizuku 或 Root；NORMAL 模式受应用沙箱限制。
- 音频与图片输入依赖所选模型的多模态能力。
- GenOffice 文档生成需要可访问的 MCP 服务。
- 文生图依赖厂商生图 API，各平台支持的尺寸与数量不同。
- 应用不提供云同步，数据随应用卸载删除。

## 来源与许可

MIT。无障碍与 Shizuku 执行模块参考并改编自 [Soodok/Deepseek-Harness-Local-Android](https://github.com/Soodok/Deepseek-Harness-Local-Android)（MIT）。Shizuku API 来自 [RikkaApps/Shizuku-API](https://github.com/RikkaApps/Shizuku-API)。

界面参考 DeepSeek 官方样式，令牌来源见 `DESIGN.md`。内置 `impeccable` 技能移植自 [pbakaus/impeccable](https://github.com/pbakaus/impeccable)（Apache-2.0）。

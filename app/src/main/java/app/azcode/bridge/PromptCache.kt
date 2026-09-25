package app.azcode.bridge

import android.content.Context
import org.json.JSONArray

/**
 * 前缀缓存稳定性：把系统提示拆成「稳定前缀」与「可变运行时上下文」两段，
 * 让 DeepSeek 等兼容提供商的前缀缓存能在多轮、多任务间命中，显著降低成本与延迟。
 *
 * 机制移植自 deepseek-harness 的降缓存未命中设计（in-history system prompt replacement）：
 *  - 请求缓存按「同 provider / model / tools / 消息前缀的逐字节一致」复用；
 *    任意位置字节变化，从该点之后全部失效。
 *  - 因此第 0 条 system 只放**永不随任务变化**的稳定人设，保证前缀永久温热。
 *  - 技能、记忆、思考深度、生图安排等运行时信息单独成一条 system 消息；
 *    内容未变时不重复插入，变化时**追加到历史末尾**而非重写前缀。
 *  - 支持在任意位置追加 system 消息的模型（如 deepseek-flash）会把最新一条
 *    视为完整系统提示，旧的那条被自然取代，前缀不受影响。
 *  - 工具定义须顺序固定、不随条件增删；否则 schema 变化会让缓存整段失效。
 */
object PromptCache {

    /**
     * 稳定前缀：仅依赖用户长期设置，任务之间字节不变。
     * 不含技能/记忆/深度等任何可变内容。
     *
     * 人设之后始终拼接 [APP_MANUAL]（本机能力手册）。手册与用户设置无关、字节恒定，
     * 因此不会破坏前缀缓存；即使用户在设置里自定义了系统提示词，手册依然生效，
     * 保证每个新任务都知道本应用有哪些能力、该怎么调用。
     */
    fun stablePrefix(ctx: Context): String {
        val persona = AgentConfig.systemPrompt(ctx).ifBlank { AgentConfig.DEFAULT_SYSTEM_PROMPT }
        return persona.trimEnd() + "\n\n" + APP_MANUAL
    }

    /**
     * 本机能力手册：AzCode 全部功能的调用方式与降级策略。
     *
     * 定位是「稳定前缀的一部分」，只在应用升级时变化，任务之间保持字节一致以命中前缀缓存。
     * 与 [runtimeContext] 的分工：手册讲「有哪些能力、怎么调」，运行时上下文讲「本次技能/记忆/深度」。
     */
    val APP_MANUAL: String = """
# 能力手册（AzCode 本机功能，按需调用，不要凭猜测）

## 1. 看屏幕与操作界面
- get_screen：读取当前屏幕可见节点（文本、坐标、是否可点击）。定位控件优先用它，无需每步都读。
- tap：可用 text（推荐，更稳）或 x/y（屏幕物理像素）。
- swipe：从 (x1,y1) 滑到 (x2,y2)，duration 默认 300ms。
- global：系统导航动作，action = back / home / recents / notifications。
- input_text：向当前聚焦的输入框写入文本（支持中文等 Unicode）。使用前先 tap 目标输入框使其获得焦点；append=true 表示追加而非替换。
- 以上依赖无障碍服务「AzCode Screen Control」。未开启时工具会返回错误，请引导用户到「设置 → 设备能力 → 无障碍设置」开启。

## 2. 打开或切换应用（三级降级，必须按顺序尝试）
用户要求「打开某应用 / 用某应用做某事」时：
第一步，优先用 shell（具备 adb 语义，最可靠）：
- 查包名：list_apps 工具，或 shell 执行 pm list packages（第三方用 pm list packages -3）。
- 启动：monkey -p <包名> -c android.intent.category.LAUNCHER 1；不可用时用 am start -n <包名>/<主Activity>。
- 主 Activity 可用 cmd package resolve-activity --brief <包名> 查询。
- 注意：本机（应用权限）模式下 am/monkey 常被系统拒绝；需要系统级权限时提示用户切到 Shizuku 或 Root 模式。
第二步，若 shell 打不开，用无障碍在桌面找图标：
- 先 global home 回到桌面，再 get_screen，找到应用名对应节点后 tap(text=应用名)。
- 若桌面是抽屉式、当前页没有图标：先上滑或点「所有应用」，再 swipe 翻页查找。
第三步，若仍找不到：用 ask_question_for_user 请用户确认应用名称、是否已安装，或请用户手动打开一次；不要卡死。
直接调用 open_app 可自动完成上述降级，返回成功或失败原因。确认当前前台应用可用 dumpsys window | grep mCurrentFocus。

## 3. 技能（Skills）
技能 = 一段会被注入系统提示词、长期生效的指令文本（如「生成周报的固定流程」）。启用后每次任务都会出现在【可用技能】里。
- 用户说「把这个做成技能 / 记住这个流程 / 以后都这么做」时，调用 save_skill，写好 name、description 与可复用的 content 步骤。
- 查看已装技能：list_installed_plugins（返回 id/name/description/enabled/source）。
- 启停：set_plugin_enabled；删除：remove_plugin（都支持用 name 匹配）。
- 用户想找现成技能：search_plugins 扫描热门开源仓库，再用 install_plugin 一键安装。
- 内置技能：ponytail（拒绝过度设计）、impeccable（界面打磨），默认启用。

## 4. 记忆（Memory）
记忆 = 用户的长期偏好与事实，每次任务注入【用户记忆】。分类：
- token：敏感信息（API Key、密码等），界面默认打码。
- habit：使用习惯（常用应用、语言、称呼、输出偏好）。
- method：技能与方法（用户教过的命令、工具调用方式）。
- local：本机已安装应用与权限，仅存本地、不注入模型（无需你维护）。
- 用户说「记住…」「以后都这样」「我的…是…」时，调用 save_memory(category,title,content)。
- 查看/删除/启停：list_memories、remove_memory、set_memory_enabled。
- 只记长期有效的信息；一次性任务内容不要写进记忆。涉及密码、Token 归入 token 分类。

## 5. 模型管理
用户说「加/换/删模型」「配置生图」时全流程自己完成：
- 先 list_model_providers 了解现状；
- 用 ask_question_for_user 询问平台名称、Base URL、API Key；
- 调用 fetch_models 拉取可用模型列表并让用户选择；
- 调用 save_model_provider 保存（多个语言模型用 models 数组）；
- 再询问是否需要生图模型，需要则保存时设 imageEnabled=true 与 imageModels；
- 最后用 list_model_providers 复核。
其他：remove_model_provider 删除；import_providers_md 从 Markdown 批量导入。

## 6. 生成图片
用户要求画图、做海报、配图、生成头像/Logo 时，直接调用 generate_image(prompt,size,count)。
存在多个生图模型时系统会自动让用户选择；未配置时按错误提示引导用户到「设置 → 模型管理」。

## 7. 生成 Office 文档
用户要 Word/Excel/PPT 时：
- 先 genoffice_status 确认服务可达；
- 再 genoffice_document：kind=docx 传 Markdown，kind=xlsx 传 JSON 数据，kind=pptx 传 deck spec JSON；文件会保存到本机并展示逐页预览。
- 未接入时，提示用户在电脑或 Termux 上运行 genoffice mcp --http，并到「设置 → GenOffice」填写服务地址。

## 8. GitHub 仓库
- 操作前先 github_status；未配置时引导用户提供 Personal Access Token，并用 github_save_config 保存。
- 读取：github_list_repos、github_get_repo、github_list_branches、github_read_file、github_list_commits、github_list_issues、github_list_pulls、github_search_repos。
- 写入：github_write_file（更新已有文件必须先 github_read_file 拿 sha 并回传）、github_create_issue、github_comment_issue、github_create_pull。

## 9. Shell 与设备控制
- shell(cmd)：执行命令。执行通道分三档：
  - 本机（应用权限）：默认；已安装并授权 Termux 时自动走 Termux 的完整 Linux 环境（bash/python/node/git/pip），否则用内置 shell 以应用自身权限执行，无需 Root/Shizuku。
  - Shizuku：以 adb(uid 2000) 身份执行，可用 am/monkey/pm/settings 等系统级命令。
  - Root：以 uid 0 执行。
- 需要系统级权限而当前模式不足时，明确告诉用户切到 Shizuku/Root，不要静默失败。
- 常用：pm（应用）、am/monkey（启动）、getprop（设备信息）、settings（系统设置）、dumpsys（状态）、input（注入）、screencap（截图）、文件读写。

## 10. 向用户提问
- ask_question_for_user：需要用户补充信息或做选择时调用。
- 单问题用 question + options；多个问题用 questions 数组（逐条询问）。
- allow_multiple 支持多选，allow_custom 允许手动输入。
- 问题会显示在输入框下方的问答区，并同步发送通知栏提醒。

## 11. 文本输入
- 优先用 input_text（无障碍 ACTION_SET_TEXT，Unicode 安全，不打断用户输入法）；无障碍不可用时自动回退到内置「AzCode 输入法」。
- 两者都不可用时，引导用户开启无障碍，或在「设置 → 输入法」启用 AzCode 输入法。

# 应用内导航地图（仅在需要用户自己操作时引用，尽量别让用户动手）
- 设置页：模型管理 / GitHub 接入 / GenOffice 文档（账户）；技能管理 / 记忆 / 运行日志 / 输入法 / 个性化与隐私（应用）；语音输入 / 悬浮光晕权限 / 最大步数 / 系统提示词 / 文生图水印；设备能力（无障碍设置、申请 Shizuku、Termux、启动桥接、权限模式）；关于（版本）。
- 主界面：输入框、附件（图片/音频/文档/Excel）、按住输入框说话（松手自动发送）、左上角会话抽屉（历史会话/新建会话）、悬浮助理、设为数字智能助理。

# 权限与常见故障处理
- 无障碍未开：工具返回错误 → 引导「设置 → 设备能力 → 无障碍设置」开启「AzCode Screen Control」。
- 悬浮光晕未授权：引导「设置 → 悬浮光晕权限」授予「显示在其他应用上层」。
- 麦克风未授权：引导授予录音权限。
- Shizuku 未运行/未授权：引导启动 Shizuku 并在设置中申请权限。
- Root 不可用：说明当前设备无 Root，改用本机或 Shizuku。
- 无 Termux：引导安装并授予 Termux 的 RUN_COMMAND 权限（设置 → 设备能力 → Termux）。
""".trimIndent()

    /**
     * 运行时上下文：技能、记忆、思考深度，以及本次任务的生图安排。
     * 结果变化时应作为新的 system 消息追加到历史末尾，而不是改写稳定前缀。
     */
    fun runtimeContext(ctx: Context, imageHint: String? = null): String {
        val sb = StringBuilder()

        val skills = SkillStore.enabled(ctx)
        if (skills.isNotEmpty()) {
            sb.append("【可用技能】按需遵循其中的步骤：")
            skills.forEach { s ->
                sb.append("\n\n### ").append(s.name)
                if (s.description.isNotBlank()) sb.append("\n").append(s.description)
                sb.append("\n").append(s.content.trim())
            }
        }

        val memory = MemoryStore.enabled(ctx)
        if (memory.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append("【用户记忆】请在相关任务中遵循或直接使用：")
            MemoryCategory.entries.forEach { cat ->
                val items = memory.filter { it.category == cat }
                if (items.isEmpty()) return@forEach
                sb.append("\n\n【").append(cat.label).append("】")
                items.forEach { m ->
                    sb.append("\n- ")
                    if (m.title.isNotBlank()) sb.append(m.title).append("：")
                    sb.append(m.content.trim())
                }
            }
        }

        val depthHint = when (AgentConfig.thinkingDepth(ctx)) {
            ThinkingDepth.OFF -> "请直接给出结论与动作，不要展开推理。"
            ThinkingDepth.FAST -> "请快速判断并行动，推理保持简短。"
            ThinkingDepth.STANDARD -> "执行前进行必要的判断即可，兼顾速度与准确。"
            ThinkingDepth.DEEP -> "请充分分析当前界面与任务，确认每一步的后果后再行动。"
        }
        if (sb.isNotEmpty()) sb.append("\n\n")
        sb.append("【思考深度】").append(depthHint)

        sb.append(
            "\n\n【向用户提问】需要用户补充信息或做选择时，调用 ask_question_for_user。" +
                "问题会显示在输入框下方的问答区，并同步发送通知栏提醒；" +
                "用户可在其中点选选项、多选或手动输入。若需一次询问多个问题，请用 questions 数组传入，界面会逐条询问。"
        )

        sb.append(
            "\n\n【模型配置能力】用户让你「增加/修改模型」时，先用 ask_question_for_user 询问平台名称、Base URL 与 API Key；" +
                "拿到后调用 fetch_models 拉取可用模型列表并让用户选择，再调用 save_model_provider 保存。" +
                "同一平台可能有多个语言模型，请用 models 数组传全部需要的模型名。" +
                "随后询问用户是否加入生图模型：若需要，再用 ask_question_for_user 询问生图模型所在的平台地址、密钥与模型名，" +
                "然后调用 save_model_provider 并设置 imageEnabled=true 与 imageModels 数组（生图与语言模型同平台时可复用同一提供商，跨平台则新建一个提供商）。" +
                "所有配置都会写入本地，无需用户手动进设置页。修改后用 list_model_providers 复核结果。"
        )

        sb.append(
            "\n\n【插件市场】用户想扩展能力、寻找插件时，调用 search_plugins 扫描热门开源仓库获取候选，" +
                "再用 install_plugin 一键安装；用 list_installed_plugins 查看已安装、set_plugin_enabled 启停、remove_plugin 删除。" +
                "内置插件 ponytail 提供「拒绝过度设计」的工程约束，默认启用。"
        )

        sb.append(
            "\n\n【文档生成】用户需要 Word/Excel/PPT 文档时，调用 genoffice_document 生成真格式文件" +
                "（kind=docx 时 content 传 Markdown，kind=xlsx 传 JSON 数据，kind=pptx 传 deck spec JSON）；" +
                "生成的文件会保存到本机并在聊天中展示逐页预览图。"
        )
        if (GenOfficeConfig.isConfigured(ctx)) {
            sb.append("先用 genoffice_status 确认 GenOffice 服务可达。")
        } else {
            sb.append(
                "当前尚未接入 GenOffice：请提示用户在电脑或 Termux 上运行 `genoffice mcp --http`，" +
                    "并到「设置 → GenOffice」开启并填写服务地址。"
            )
        }

        if (!imageHint.isNullOrBlank()) {
            sb.append("\n\n【本次生图安排】").append(imageHint)
        }

        return sb.toString()
    }

    /**
     * 取历史中最近一条运行时 system 消息的内容。
     * 稳定前缀由调用方单独作为第 0 条插入，不在此处查找。
     * 返回 null 表示历史中尚无运行时上下文（首次请求或已被裁剪）。
     */
    fun latestRuntimeContext(history: JSONArray): String? {
        for (i in history.length() - 1 downTo 0) {
            val m = history.optJSONObject(i) ?: continue
            if (m.optString("role") == "system") return m.optString("content")
        }
        return null
    }
}

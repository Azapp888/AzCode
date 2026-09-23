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
     */
    fun stablePrefix(ctx: Context): String =
        AgentConfig.systemPrompt(ctx).ifBlank { AgentConfig.DEFAULT_SYSTEM_PROMPT }

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

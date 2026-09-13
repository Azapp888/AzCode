package app.azcode.bridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Agent 决策循环向 UI 暴露的结构化事件。
 *
 * 设计意图：区分「给用户看的内容」与「工具执行细节」——
 *  - AssistantText → 聊天气泡
 *  - ToolStart / ToolResult → 可折叠的工具卡片
 *  - Thinking → 临时「正在思考」指示器
 *  - Notice / Failure → 居中的系统提示
 */
sealed interface AgentEvent {
    /** 正在等待模型响应，UI 显示思考指示器。 */
    object Thinking : AgentEvent

    /** 模型对用户说的话，直接展示为气泡。 */
    data class AssistantText(val text: String) : AgentEvent

    /** 开始执行某个工具。 */
    data class ToolStart(val name: String, val args: String) : AgentEvent

    /** 工具执行结束。 */
    data class ToolResult(val name: String, val ok: Boolean, val output: String) : AgentEvent

    /** 文生图工具产出的图片地址列表，UI 直接渲染为图片气泡。 */
    data class Images(val urls: List<String>) : AgentEvent

    /** 中性系统提示，如达到步数上限。 */
    data class Notice(val text: String) : AgentEvent

    /** 失败提示。 */
    data class Failure(val message: String) : AgentEvent
}

/**
 * Agent 向用户提出的选择题。UI 在输入框下方展开一块区域呈现，并同步发送通知栏提醒。
 * [allowMultiple] 允许多选，[allowCustom] 允许用户手动输入答案。
 * [index]/[total] 用于一次传入多个问题、逐条询问时提示进度（0 表示单条）。
 */
data class AgentQuestion(
    val question: String,
    val options: List<String>,
    val allowMultiple: Boolean = false,
    val allowCustom: Boolean = true,
    val index: Int = 0,
    val total: Int = 0,
)

/**
 * 设备端 Agent 决策循环：按需读取屏幕 → 交 DeepSeek 决策 → 直接调用本机无障碍/Shizuku 执行 → 回灌结果。
 *
 * 会话续接：每个任务的上下文（历史消息）保存在 [ChatSession.agentMessages] 中，
 * 下一轮请求会在系统提示词之后接续这些历史，从而让同一会话里的多轮对话保持连贯。
 * 系统提示词不持久化，每次根据最新的技能/记忆重新构建。
 *
 * [askUser] 在工作线程上调用并阻塞，直到用户在输入框下方的问答区作答（返回 null 表示未作答）。
 */
class AgentRunner(
    private val ctx: Context,
    private val askUser: ((AgentQuestion) -> String?)? = null,
    private val listener: (AgentEvent) -> Unit,
) {
    @Volatile private var cancelled = false

    /** 本次任务选定的生图提供商与模型（用户要求生成图片时解析）。 */
    private var selectedImageProvider: ProviderAccount? = null
    private var selectedImageModel: String? = null

    private companion object {
        /** 上下文估算字符数超过该阈值时触发压缩。 */
        const val COMPACT_THRESHOLD = 40000
        /** 压缩时保留最近若干个 user 轮次不参与摘要。 */
        const val KEEP_USER_TURNS = 4
    }

    fun cancel() {
        cancelled = true
    }

    fun run(task: String, attachments: List<AttachmentReader.Prepared> = emptyList(), session: ChatSession? = null) {
        val provider = AgentConfig.activeProvider(ctx)
        if (provider == null) {
            listener(AgentEvent.Failure("尚未配置模型提供商，请到「设置 → 模型管理」添加并启用一个提供商"))
            return
        }
        val maxSteps = AgentConfig.maxSteps(ctx)

        // 用户明确要求生成图片时，提前解析并选定生图模型（多个则询问用户）。
        val imageHint = resolveImageIntent(task)

        var messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", buildSystemPrompt(imageHint)))
            appendHistory(session)
            put(JSONObject().put("role", "user").put("content", DeepSeekClient.buildUserContent(task, attachments)))
        }
        val tools = buildTools()
        val depth = AgentConfig.thinkingDepth(ctx)
        val effort = if (provider.supportsReasoningEffort) depth.effort else null

        try {
            var step = 0
            while (maxSteps <= 0 || step < maxSteps) {
                step++
                if (cancelled) { listener(AgentEvent.Notice("已停止")); return }
                listener(AgentEvent.Thinking)

                messages = compactIfNeeded(messages, provider)

                val reply = try {
                    DeepSeekClient.chat(provider, messages, tools, effort)
                } catch (e: Exception) {
                    if (cancelled) listener(AgentEvent.Notice("已停止"))
                    else listener(AgentEvent.Failure(e.message ?: "请求模型失败"))
                    return
                }
                if (cancelled) { listener(AgentEvent.Notice("已停止")); return }

                messages.put(assistantMessage(reply))

                if (!reply.content.isNullOrBlank()) {
                    listener(AgentEvent.AssistantText(reply.content.trim()))
                }

                if (reply.toolCalls.isEmpty()) return

                for (c in reply.toolCalls) {
                    if (cancelled) { listener(AgentEvent.Notice("已停止")); return }
                    listener(AgentEvent.ToolStart(c.name, c.arguments))

                    val result = execute(c)
                    val ok = runCatching { JSONObject(result).optBoolean("ok", false) }.getOrDefault(false)

                    // 文生图结果可能包含大体积 base64，仅把图片交给 UI，回灌模型的只保留简短说明。
                    var modelResult = result
                    if (c.name == "generate_image" && ok) {
                        val urls = runCatching {
                            val arr = JSONObject(result).optJSONArray("images") ?: JSONArray()
                            (0 until arr.length()).map { arr.getString(it) }
                        }.getOrDefault(emptyList())
                        if (urls.isNotEmpty()) listener(AgentEvent.Images(urls))
                        modelResult = JSONObject()
                            .put("ok", true)
                            .put("message", "已生成 ${urls.size} 张图片并展示给用户")
                            .toString()
                    }
                    listener(AgentEvent.ToolResult(c.name, ok, modelResult))

                    messages.put(JSONObject().apply {
                        put("role", "tool")
                        put("tool_call_id", c.id)
                        put("content", modelResult)
                    })

                    if (c.name == "finish") {
                        val summary = runCatching { JSONObject(c.arguments).optString("summary") }
                            .getOrDefault("")
                        if (summary.isNotBlank()) listener(AgentEvent.AssistantText(summary))
                        return
                    }
                }
            }
            listener(AgentEvent.Notice("已达到最大步数 $maxSteps，任务停止"))
        } finally {
            if (session != null) persistHistory(session, messages)
        }
    }

    // ==================== 会话上下文 ====================

    /** 将之前保存的历史消息接续到本轮消息之前（跳过系统提示词）。 */
    private fun JSONArray.appendHistory(session: ChatSession?) {
        if (session == null) return
        val prior = runCatching { JSONArray(session.agentMessages) }.getOrNull() ?: return
        for (i in 0 until prior.length()) {
            val m = prior.optJSONObject(i) ?: continue
            if (m.optString("role") == "system") continue
            put(m)
        }
    }

    /** 把本轮消息写回会话；剥离图片等大体积内容并限制历史长度。 */
    private fun persistHistory(session: ChatSession, messages: JSONArray) {
        val kept = JSONArray()
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            val role = m.optString("role")
            if (role == "system") continue
            kept.put(sanitize(m))
        }
        // 仅保留最近一段，避免文件无限增长；裁剪后若以 tool 开头则丢弃这些孤立结果。
        val max = 40
        val trimmed = JSONArray()
        val start = if (kept.length() > max) kept.length() - max else 0
        var began = false
        for (i in start until kept.length()) {
            val m = kept.optJSONObject(i) ?: continue
            if (!began && m.optString("role") == "tool") continue
            began = true
            trimmed.put(m)
        }
        session.agentMessages = trimmed.toString()
    }

    /** 用文本占位替换图片 base64，避免持久化文件过大。 */
    private fun sanitize(m: JSONObject): JSONObject {
        if (m.optString("role") != "user") return m
        val content = m.opt("content") ?: return m
        if (content !is JSONArray) return m
        val out = JSONArray()
        for (i in 0 until content.length()) {
            val part = content.optJSONObject(i) ?: continue
            if (part.optString("type") == "image_url") {
                out.put(JSONObject().put("type", "text").put("text", "[图片]"))
            } else {
                out.put(part)
            }
        }
        return JSONObject().put("role", "user").put("content", out)
    }

    private fun assistantMessage(reply: AssistantReply): JSONObject = JSONObject().apply {
        put("role", "assistant")
        put("content", reply.content ?: JSONObject.NULL)
        if (reply.toolCalls.isNotEmpty()) {
            put("tool_calls", JSONArray().apply {
                reply.toolCalls.forEach { c ->
                    put(JSONObject().apply {
                        put("id", c.id)
                        put("type", "function")
                        put("function", JSONObject()
                            .put("name", c.name)
                            .put("arguments", c.arguments))
                    })
                }
            })
        }
    }

    // ==================== 工具执行 ====================

    private fun execute(call: ToolCall): String {
        val args = runCatching { JSONObject(call.arguments.ifBlank { "{}" }) }.getOrElse { JSONObject() }
        return try {
            when (call.name) {
            "get_screen" -> AzAccessibilityService.instance?.dumpScreenJson()
                ?: err("无障碍服务未开启，无法读取屏幕。请到「设置 → 设备能力 → 无障碍设置」开启 AzCode Screen Control 后重试。")

            "tap" -> {
                val svc = AzAccessibilityService.instance
                    ?: return err("无障碍服务未开启，无法点击。请到「设置 → 设备能力 → 无障碍设置」开启后重试。")
                when {
                    args.has("text") ->
                        if (svc.tapText(args.getString("text"))) ok() else err("未找到文本：${args.getString("text")}")
                    args.has("x") && args.has("y") ->
                        if (svc.dispatchTap(args.getDouble("x").toFloat(), args.getDouble("y").toFloat())) ok()
                        else err("点击派发失败")
                    else -> err("需要 text 或 x/y")
                }
            }

            "swipe" -> {
                val svc = AzAccessibilityService.instance
                    ?: return err("无障碍服务未开启，无法滑动。请到「设置 → 设备能力 → 无障碍设置」开启后重试。")
                val okSwipe = svc.dispatchSwipe(
                    args.getDouble("x1").toFloat(),
                    args.getDouble("y1").toFloat(),
                    args.getDouble("x2").toFloat(),
                    args.getDouble("y2").toFloat(),
                    args.optLong("duration", 300),
                )
                if (okSwipe) ok() else err("滑动派发失败")
            }

            "global" -> {
                val svc = AzAccessibilityService.instance
                    ?: return err("无障碍服务未开启，无法执行系统导航。请到「设置 → 设备能力 → 无障碍设置」开启后重试。")
                if (svc.globalAction(args.optString("action"))) ok() else err("未知动作")
            }

            "shell" -> {
                val cmd = args.optString("cmd")
                if (cmd.isBlank()) err("缺少 cmd")
                else JSONObject().put("ok", true).put("output", DeviceControl.exec(ctx, cmd)).toString()
            }

            "generate_image" -> {
                val prompt = args.optString("prompt")
                val imageProvider = selectedImageProvider ?: AgentConfig.imageProvider(ctx)
                when {
                    prompt.isBlank() -> err("缺少 prompt")
                    imageProvider == null -> err("当前未配置文生图，请在「设置 → 模型管理」中为某个提供商开启生图模型")
                    else -> {
                        val model = selectedImageModel
                            ?.takeIf { imageProvider.allImageModels.contains(it) }
                            ?: imageProvider.imageModel
                        val urls = DeepSeekClient.generateImage(
                            provider = imageProvider.copy(imageModel = model),
                            prompt = prompt,
                            size = args.optString("size", "1024x1024"),
                            count = args.optInt("count", 1),
                        )
                        JSONObject().put("ok", true).put("images", JSONArray(urls)).toString()
                    }
                }
            }

            "finish" -> JSONObject().put("ok", true).put("summary", args.optString("summary")).toString()

            "ask_question_for_user" -> askQuestion(args)

            "list_model_providers" -> listModelProviders()

            "fetch_models" -> fetchModels(args)

            "save_model_provider" -> saveModelProvider(args)

            "remove_model_provider" -> removeModelProvider(args)

            "import_providers_md" -> importProvidersMd(args)

            else -> err("未知工具 ${call.name}")
            }
        } catch (e: Exception) {
            err(e.message ?: "执行异常")
        }
    }

    private fun ok() = """{"ok":true}"""

    private fun err(message: String) = JSONObject().put("ok", false).put("error", message).toString()

    // ==================== 生图意图解析 ====================

    /** 判断用户的话是否在要求生成图片（而非分析已有图片）。 */
    private fun looksLikeImageRequest(task: String): Boolean {
        val t = task.lowercase()
        if (t.contains("生图") || t.contains("文生图") || t.contains("generate image")) return true
        val verbs = listOf("生成", "画一", "画个", "画张", "画只", "绘制", "制作", "设计", "做一张", "来一张", "帮我画", "create", "generate", "draw")
        val nouns = listOf("图片", "图像", "海报", "插画", "logo", "图标", "表情包", "头像", "image", "picture", "poster", "illustration")
        return verbs.any { t.contains(it) } && nouns.any { t.contains(it) }
    }

    /**
     * 用户要求生成图片且存在可用生图模型时，选定本次使用的模型。
     * 存在多个候选时通过 ask_question_for_user 让用户选择。返回给模型的提示文本。
     */
    private fun resolveImageIntent(task: String): String? {
        if (!looksLikeImageRequest(task)) return null
        val candidates = AgentConfig.imageCandidates(ctx)
        if (candidates.isEmpty()) return null

        val chosen: Pair<ProviderAccount, String> = if (candidates.size == 1) {
            candidates.first()
        } else {
            val handler = askUser ?: return null
            val options = candidates.map { "${it.first.name} · ${it.second}" }
            val answer = handler(
                AgentQuestion(
                    question = "检测到多个可用的生图模型，请选择用于本次生成图片的模型：",
                    options = options,
                    allowMultiple = false,
                    allowCustom = false,
                )
            )
            val idx = options.indexOf(answer)
            if (idx in candidates.indices) candidates[idx] else return null
        }

        selectedImageProvider = chosen.first
        selectedImageModel = chosen.second
        return "用户本次要求生成图片，请直接调用 generate_image 工具，prompt 使用用户的描述。" +
            "本次使用生图模型：${chosen.first.name} · ${chosen.second}。"
    }

    // ==================== 向用户提问 / 模型配置工具 ====================

    private fun askQuestion(args: JSONObject): String {
        val handler = askUser ?: return err("当前环境无法向用户提问")

        // 收集问题：优先 questions 数组（逐条询问），否则取单个 question。
        val questions = mutableListOf<AgentQuestion>()
        args.optJSONArray("questions")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val q = o.optString("question").trim()
                if (q.isNotEmpty()) questions.add(parseQuestion(o, q))
            }
        }
        if (questions.isEmpty()) {
            val q = args.optString("question").trim()
            if (q.isEmpty()) return err("缺少 question")
            questions.add(parseQuestion(args, q))
        }

        val answers = JSONArray()
        val plain = mutableListOf<String>()
        questions.forEachIndexed { i, base ->
            val q = base.copy(
                index = if (questions.size > 1) i + 1 else 0,
                total = if (questions.size > 1) questions.size else 0,
            )
            val answer = handler(q)
            val shown = if (answer.isNullOrBlank()) "未作答" else answer
            plain.add(if (questions.size > 1) "${q.question}：$shown" else shown)
            answers.put(JSONObject().put("question", q.question).put("answer", answer))
        }

        val combined = plain.joinToString("\n")
        return if (plain.all { it == "未作答" }) {
            JSONObject().put("ok", false).put("error", "用户未作答").put("answers", answers).toString()
        } else {
            JSONObject().put("ok", true).put("answer", combined).put("answers", answers).toString()
        }
    }

    private fun parseQuestion(o: JSONObject, question: String): AgentQuestion {
        val options = mutableListOf<String>()
        o.optJSONArray("options")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.optString(i).trim()
                if (s.isNotEmpty()) options.add(s)
            }
        }
        return AgentQuestion(
            question = question,
            options = options,
            allowMultiple = o.optBoolean("allow_multiple", false),
            allowCustom = o.optBoolean("allow_custom", true),
        )
    }

    private fun listModelProviders(): String {
        val list = AgentConfig.providers(ctx)
        val active = AgentConfig.activeId(ctx)
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("protocol", p.protocol.key)
                put("baseUrl", p.baseUrl)
                put("model", p.model)
                put("enabled", p.enabled)
                put("active", p.id == active)
                put("imageEnabled", p.hasImage)
                put("imageModel", p.imageModel)
                put("hasApiKey", p.apiKey.isNotBlank())
            })
        }
        return JSONObject().put("ok", true).put("providers", arr).toString()
    }

    private fun fetchModels(args: JSONObject): String {
        val baseUrl = args.optString("baseUrl").ifBlank {
            AgentConfig.activeProvider(ctx)?.baseUrl ?: ""
        }
        val apiKey = args.optString("apiKey").ifBlank {
            AgentConfig.activeProvider(ctx)?.apiKey ?: ""
        }
        val protocol = ProviderProtocol.from(
            args.optString("protocol").ifBlank {
                AgentConfig.activeProvider(ctx)?.protocol?.key
            }
        )
        if (baseUrl.isBlank()) return err("缺少 baseUrl")
        val models = DeepSeekClient.listModels(protocol, baseUrl, apiKey)
        return JSONObject().put("ok", true)
            .put("models", JSONArray(models))
            .put("count", models.size)
            .toString()
    }

    private fun saveModelProvider(args: JSONObject): String {
        val protocol = ProviderProtocol.from(args.optString("protocol", "openai"))
        val baseUrl = args.optString("baseUrl")
        val models = stringArray(args.optJSONArray("models")).ifEmpty {
            listOfNotNull(args.optString("model").takeIf { it.isNotBlank() })
        }
        if (baseUrl.isBlank()) return err("缺少 baseUrl")
        if (models.isEmpty()) return err("缺少 model/models")
        val imageEnabled = args.optBoolean("imageEnabled", false) && protocol.supportsImage
        val imageModels = if (imageEnabled) {
            stringArray(args.optJSONArray("imageModels")).ifEmpty {
                listOfNotNull(args.optString("imageModel").takeIf { it.isNotBlank() })
            }
        } else emptyList()
        if (imageEnabled && imageModels.isEmpty()) return err("已开启生图但缺少 imageModel/imageModels")

        val model = args.optString("model").takeIf { it.isNotBlank() } ?: models.first()
        val imageModel = imageModels.firstOrNull().orEmpty()
        val name = args.optString("name").ifBlank { protocol.label }
        val existingId = args.optString("id")
        val existing = AgentConfig.providers(ctx).firstOrNull { it.id == existingId }
            ?: AgentConfig.providers(ctx).firstOrNull { it.name == name && it.baseUrl == baseUrl }

        val account = ProviderAccount(
            id = existing?.id ?: AgentConfig.newAccountId(),
            name = name,
            protocol = protocol,
            baseUrl = baseUrl,
            apiKey = args.optString("apiKey").ifBlank { existing?.apiKey ?: "" },
            enabled = args.optBoolean("enabled", true),
            models = models,
            model = model,
            supportsReasoningEffort = args.optBoolean(
                "supportsReasoningEffort",
                existing?.supportsReasoningEffort ?: (protocol == ProviderProtocol.OPENAI && baseUrl.contains("deepseek")),
            ),
            imageEnabled = imageEnabled,
            imageModels = imageModels,
            imageModel = imageModel,
        )
        AgentConfig.upsertProvider(ctx, account)
        if (args.optBoolean("setActive", false) || AgentConfig.activeProvider(ctx) == null) {
            AgentConfig.setActiveId(ctx, account.id)
        }
        return JSONObject().put("ok", true)
            .put("id", account.id)
            .put("name", account.name)
            .put("models", account.models.size)
            .put("imageModels", account.imageModels.size)
            .toString()
    }

    private fun stringArray(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = LinkedHashSet<String>()
        for (i in 0 until arr.length()) {
            val s = arr.optString(i).trim()
            if (s.isNotEmpty()) out.add(s)
        }
        return out.toList()
    }

    private fun removeModelProvider(args: JSONObject): String {
        val id = args.optString("id")
        val name = args.optString("name")
        val target = AgentConfig.providers(ctx).firstOrNull {
            (id.isNotBlank() && it.id == id) || (name.isNotBlank() && it.name == name)
        } ?: return err("未找到该提供商")
        AgentConfig.removeProvider(ctx, target.id)
        return JSONObject().put("ok", true).put("name", target.name).toString()
    }

    private fun importProvidersMd(args: JSONObject): String {
        val content = args.optString("content")
        if (content.isBlank()) return err("缺少 content")
        val accounts = ProviderImporter.parse(ctx, content)
        if (accounts.isEmpty()) return err("未从文档中解析出任何提供商")
        accounts.forEach { AgentConfig.upsertProvider(ctx, it) }
        val names = JSONArray()
        accounts.forEach { names.put(it.name) }
        return JSONObject().put("ok", true)
            .put("imported", accounts.size)
            .put("names", names)
            .toString()
    }

    // ==================== 上下文压缩 ====================

    /**
     * 当上下文过大时，把较早的对话（按 user 轮次切分，保证 tool 调用与结果不被拆散）
     * 交给模型压缩成一段摘要，替换掉原始消息，从而在长任务中继续推进。
     */
    private fun compactIfNeeded(messages: JSONArray, provider: ProviderAccount): JSONArray {
        if (estimateChars(messages) < COMPACT_THRESHOLD) return messages
        if (messages.length() <= 1) return messages

        val userIndices = mutableListOf<Int>()
        for (i in 1 until messages.length()) {
            if (messages.optJSONObject(i)?.optString("role") == "user") userIndices.add(i)
        }
        if (userIndices.size <= KEEP_USER_TURNS) return messages
        val boundary = userIndices[userIndices.size - KEEP_USER_TURNS]
        if (boundary <= 1) return messages

        val older = JSONArray()
        for (i in 1 until boundary) older.put(messages.getJSONObject(i))

        val summary = summarize(older, provider)
        val rebuilt = JSONArray()
        rebuilt.put(messages.getJSONObject(0))
        rebuilt.put(JSONObject().put("role", "user").put(
            "content",
            "[上下文摘要] 以下是本次任务较早阶段的要点，请据此继续：\n${summary.ifBlank { "（较早对话已省略）" }}"
        ))
        for (i in boundary until messages.length()) rebuilt.put(messages.getJSONObject(i))
        listener(AgentEvent.Notice("上下文过长，已自动压缩历史"))
        return rebuilt
    }

    private fun summarize(older: JSONArray, provider: ProviderAccount): String {
        val sb = StringBuilder()
        for (i in 0 until older.length()) {
            val m = older.optJSONObject(i) ?: continue
            val role = m.optString("role")
            val content = when (val c = m.opt("content")) {
                is String -> c
                is JSONArray -> (0 until c.length()).joinToString(" ") {
                    c.optJSONObject(it)?.optString("text").orEmpty()
                }
                else -> ""
            }
            if (content.isNotBlank()) sb.append(role).append(": ").append(content.take(800)).append("\n")
            m.optJSONArray("tool_calls")?.let { arr ->
                for (j in 0 until arr.length()) {
                    val fn = arr.optJSONObject(j)?.optJSONObject("function") ?: continue
                    sb.append("tool_call: ").append(fn.optString("name"))
                        .append(" ").append(fn.optString("arguments").take(200)).append("\n")
                }
            }
            if (role == "tool") {
                sb.append("tool_result: ").append(content.take(300)).append("\n")
            }
        }
        val prompt = JSONArray().apply {
            put(JSONObject().put("role", "system").put(
                "content",
                "你是上下文压缩器。把给定的对话历史压缩成简洁的中文要点，保留用户目标、已完成的操作、关键结论、待办与失败原因，省略寒暄。只输出要点正文。"
            ))
            put(JSONObject().put("role", "user").put("content", sb.toString().take(12000)))
        }
        return runCatching {
            DeepSeekClient.chat(provider, prompt, JSONArray(), null).content?.trim().orEmpty()
        }.getOrDefault("")
    }

    private fun estimateChars(messages: JSONArray): Int {
        var total = 0
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            when (val c = m.opt("content")) {
                is String -> total += c.length
                is JSONArray -> for (j in 0 until c.length()) {
                    total += c.optJSONObject(j)?.optString("text")?.length ?: 0
                }
            }
            m.optJSONArray("tool_calls")?.let { arr ->
                for (j in 0 until arr.length()) {
                    total += arr.optJSONObject(j)?.optJSONObject("function")?.optString("arguments")?.length ?: 0
                }
            }
        }
        return total
    }

    private fun buildTools(): JSONArray {
        fun fn(name: String, desc: String, props: JSONObject, required: List<String>): JSONObject = JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", name)
                put("description", desc)
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", props)
                    put("required", JSONArray(required))
                })
            })
        }
        fun num(desc: String) = JSONObject().put("type", "number").put("description", desc)
        fun str(desc: String) = JSONObject().put("type", "string").put("description", desc)

        return JSONArray().apply {
            put(fn("get_screen", "读取当前屏幕可见节点（文本、坐标、可点击性）。仅在需要观察屏幕内容或定位控件时调用，无需每一步都读取。", JSONObject(), emptyList()))
            put(fn("tap", "点击屏幕，可用坐标或文本二者之一。", JSONObject()
                .put("x", num("X 物理像素"))
                .put("y", num("Y 物理像素"))
                .put("text", str("要点击的文本")), emptyList()))
            put(fn("swipe", "从 (x1,y1) 滑动到 (x2,y2)。", JSONObject()
                .put("x1", num("起点 X"))
                .put("y1", num("起点 Y"))
                .put("x2", num("终点 X"))
                .put("y2", num("终点 Y"))
                .put("duration", num("持续毫秒，默认 300")), listOf("x1", "y1", "x2", "y2")))
            put(fn("global", "系统导航动作。", JSONObject()
                .put("action", JSONObject()
                    .put("type", "string")
                    .put("enum", JSONArray(listOf("back", "home", "recents", "notifications")))
                    .put("description", "导航动作")), listOf("action")))
            put(fn("shell", "以 Shizuku/Root 身份执行 shell 命令（需高权限模式）。", JSONObject()
                .put("cmd", str("要执行的命令")), listOf("cmd")))

            val imageModel = AgentConfig.imageProvider(ctx)
            if (imageModel != null) {
                put(fn(
                    "generate_image",
                    "根据文字描述生成图片（文生图）。当用户要求画图、生成图片、制作海报或配图时调用，生成结果会自动展示给用户。",
                    JSONObject()
                        .put("prompt", str("图片内容的详细描述，建议包含主体、风格、构图、色彩"))
                        .put("size", str("图片尺寸，如 1024x1024；不同提供商支持的尺寸可能不同，可省略"))
                        .put("count", num("生成张数，默认 1")),
                    listOf("prompt"),
                ))
            }

            put(fn("finish", "任务结束并给出总结。", JSONObject()
                .put("summary", str("结果总结")), listOf("summary")))

            put(fn(
                "ask_question_for_user",
                "当需要用户补充信息或做选择时，在输入框下方展开的问答区向用户提问，并同步发送通知栏提醒。用于需求不明确、需要用户提供地址/密钥、或需要用户确认选项的场景。",
                JSONObject()
                    .put("question", str("要询问用户的问题（单个问题时使用）"))
                    .put("questions", JSONObject()
                        .put("type", "array")
                        .put("description", "需要一次询问多个问题时使用，会逐条询问并分别收集答案")
                        .put("items", JSONObject()
                            .put("type", "object")
                            .put("properties", JSONObject()
                                .put("question", str("问题内容"))
                                .put("options", JSONObject()
                                    .put("type", "array")
                                    .put("description", "可选项列表；留空则要求用户手动输入")
                                    .put("items", JSONObject().put("type", "string")))
                                .put("allow_multiple", JSONObject().put("type", "boolean").put("description", "是否允许多选，默认 false"))
                                .put("allow_custom", JSONObject().put("type", "boolean").put("description", "是否允许用户手动输入，默认 true")))
                            .put("required", JSONArray(listOf("question")))))
                    .put("options", JSONObject()
                        .put("type", "array")
                        .put("description", "可选项列表；留空则要求用户手动输入")
                        .put("items", JSONObject().put("type", "string")))
                    .put("allow_multiple", JSONObject().put("type", "boolean").put("description", "是否允许多选，默认 false"))
                    .put("allow_custom", JSONObject().put("type", "boolean").put("description", "是否允许用户手动输入，默认 true")),
                listOf("question"),
            ))

            put(fn(
                "list_model_providers",
                "列出本机已配置的模型提供商（名称、协议、地址、语言模型、生图模型、是否启用/使用中）。在帮用户增加或修改模型前先调用它了解现状。",
                JSONObject(), emptyList(),
            ))

            put(fn(
                "fetch_models",
                "从某个提供商的 /models 接口拉取可用的模型列表。默认使用当前使用中的提供商与密钥。",
                JSONObject()
                    .put("protocol", str("协议：openai / anthropic / gemini，默认沿用当前提供商"))
                    .put("baseUrl", str("Base URL，默认沿用当前提供商"))
                    .put("apiKey", str("API Key，默认沿用当前提供商")),
                emptyList(),
            ))

            put(fn(
                "save_model_provider",
                "新增或更新一个模型提供商并写入本地配置。当用户让你增加模型时，先用 ask_question_for_user 询问平台、地址与密钥，用 fetch_models 拉取模型列表，再调用本工具保存。",
                JSONObject()
                    .put("id", str("已有提供商的 id（更新时传），新增可省略"))
                    .put("name", str("平台名称"))
                    .put("protocol", str("协议：openai / anthropic / gemini，默认 openai"))
                    .put("baseUrl", str("Base URL"))
                    .put("apiKey", str("API Key"))
                    .put("model", str("语言模型名（单个）"))
                    .put("models", JSONObject()
                        .put("type", "array")
                        .put("description", "该平台可用的语言模型名列表（可多个）")
                        .put("items", JSONObject().put("type", "string")))
                    .put("enabled", JSONObject().put("type", "boolean").put("description", "是否启用，默认 true"))
                    .put("setActive", JSONObject().put("type", "boolean").put("description", "是否设为当前使用，默认 false"))
                    .put("supportsReasoningEffort", JSONObject().put("type", "boolean").put("description", "是否支持 reasoning_effort"))
                    .put("imageEnabled", JSONObject().put("type", "boolean").put("description", "是否加入生图模型"))
                    .put("imageModel", str("生图模型名（单个）"))
                    .put("imageModels", JSONObject()
                        .put("type", "array")
                        .put("description", "该平台可用的生图模型名列表（可多个）")
                        .put("items", JSONObject().put("type", "string"))),
                listOf("name", "baseUrl"),
            ))

            put(fn(
                "remove_model_provider",
                "删除一个已配置的模型提供商。",
                JSONObject()
                    .put("id", str("提供商 id"))
                    .put("name", str("提供商名称")),
                emptyList(),
            ))

            put(fn(
                "import_providers_md",
                "从 Markdown/文本内容中批量解析并导入模型提供商配置。当用户提供或附带了一个描述多个平台的 md 文件时调用。",
                JSONObject().put("content", str("md/文本的完整内容")),
                listOf("content"),
            ))
        }
    }

    // ==================== 系统提示词 ====================

    private fun buildSystemPrompt(imageHint: String? = null): String {
        val base = AgentConfig.systemPrompt(ctx).ifBlank { AgentConfig.DEFAULT_SYSTEM_PROMPT }
        val sb = StringBuilder(base)

        val skills = SkillStore.enabled(ctx)
        if (skills.isNotEmpty()) {
            sb.append("\n\n你可以运用以下技能，按需遵循其中的步骤：")
            skills.forEach { s ->
                sb.append("\n\n### ").append(s.name)
                if (s.description.isNotBlank()) sb.append("\n").append(s.description)
                sb.append("\n").append(s.content.trim())
            }
        }

        val memory = MemoryStore.enabled(ctx)
        if (memory.isNotEmpty()) {
            sb.append("\n\n以下是用户要求你记住的信息，请在相关任务中遵循或直接使用：")
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
        sb.append("\n\n思考深度要求：").append(depthHint)

        sb.append(
            "\n\n向用户提问：需要用户补充信息或做选择时，调用 ask_question_for_user。问题会显示在输入框下方的问答区，并同步发送通知栏提醒；" +
                "用户可在其中点选选项、多选或手动输入。若需一次询问多个问题，请用 questions 数组传入，界面会逐条询问。" +
                "\n\n模型配置能力：用户让你「增加/修改模型」时，先用 ask_question_for_user 询问平台名称、Base URL 与 API Key；" +
                "拿到后调用 fetch_models 拉取可用模型列表并让用户选择，再调用 save_model_provider 保存。" +
                "同一平台可能有多个语言模型，请用 models 数组传全部需要的模型名。" +
                "随后询问用户是否加入生图模型：若需要，再用 ask_question_for_user 询问生图模型所在的平台地址、密钥与模型名，" +
                "然后调用 save_model_provider 并设置 imageEnabled=true 与 imageModels 数组（生图与语言模型同平台时可复用同一提供商，跨平台则新建一个提供商）。" +
                "所有配置都会写入本地，无需用户手动进设置页。修改后用 list_model_providers 复核结果。"
        )

        if (!imageHint.isNullOrBlank()) {
            sb.append("\n\n本次生图安排：").append(imageHint)
        }

        return sb.toString()
    }
}

package app.azcode.bridge

import android.content.Context
import app.azcode.bridge.llm.LLMContent
import app.azcode.bridge.llm.LLMService
import app.azcode.bridge.llm.core.LLMCodec
import app.azcode.bridge.llm.core.LLMRequest
import app.azcode.bridge.llm.core.LLMResponse
import app.azcode.bridge.llm.core.LLMThinking
import app.azcode.bridge.llm.core.LLMToolCall
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
 * 设备端 Agent 决策循环：按需读取屏幕 → 交大模型决策 → 直接调用本机无障碍/Shizuku 执行 → 回灌结果。
 *
 * 会话续接：每个任务的上下文（历史消息）保存在 [ChatSession.agentMessages] 中，
 * 下一轮请求会在系统提示词之后接续这些历史，从而让同一会话里的多轮对话保持连贯。
 * 系统提示按 [PromptCache] 拆成稳定前缀与运行时上下文：前缀字节恒定，运行时上下文
 * 变化时追加到历史末尾而非重写前缀，以最大化提供商前缀缓存命中率。
 *
 * [askUser] 在工作线程上调用并阻塞，直到用户在输入框下方的问答区作答（返回 null 表示未作答）。
 */
class AgentRunner(
    private val ctx: Context,
    private val askUser: ((AgentQuestion) -> String?)? = null,
    private val listener: (AgentEvent) -> Unit,
) {
    @Volatile private var cancelled = false

    /** 供 UI 判断任务是否已被请求取消，用于中断阻塞等待。 */
    val isCancelled: Boolean get() = cancelled

    /** 本次任务选定的生图提供商与模型（用户要求生成图片时解析）。 */
    private var selectedImageProvider: ProviderAccount? = null
    private var selectedImageModel: String? = null

    private companion object {
        const val TAG = "AgentRunner"
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

        // 前缀稳定：第 0 条 system 只放稳定人设；技能/记忆/深度/生图安排等作为运行时
        // 上下文，仅在内容变化时追加为新的 system 消息，绝不改写已有前缀，从而命中缓存。
        val baseSystem = PromptCache.stablePrefix(ctx)
        val runtime = PromptCache.runtimeContext(ctx, imageHint)

        var messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", baseSystem))
            appendHistory(session)
            if (PromptCache.latestRuntimeContext(this) != runtime) {
                put(JSONObject().put("role", "system").put("content", runtime))
            }
            put(JSONObject().put("role", "user").put("content", LLMContent.buildUserContent(task, attachments)))
        }
        val tools = buildTools()
        val depth = AgentConfig.thinkingDepth(ctx)

        try {
            var step = 0
            // 生图失败会作为工具结果回灌给模型，若模型反复重试会长时间空转；连续失败达到上限即停止。
            var imageFailures = 0
            while (maxSteps <= 0 || step < maxSteps) {
                step++
                if (cancelled) { listener(AgentEvent.Notice("已停止")); return }
                listener(AgentEvent.Thinking)
                CrashLog.i(TAG, "第 $step 步：请求模型 ${provider.model}")

                messages = compactIfNeeded(messages, provider)

                val reply = try {
                    LLMService.chat(
                        provider,
                        LLMRequest(
                            model = provider.model,
                            messages = LLMCodec.openAiToMessages(messages),
                            tools = LLMCodec.openAiToTools(tools),
                            thinking = LLMThinking.from(depth.key),
                            omitThinkingParam = !provider.supportsReasoningEffort,
                        ),
                    )
                } catch (e: Exception) {
                    CrashLog.w(TAG, "第 $step 步请求失败: ${e.message}", e)
                    if (cancelled) listener(AgentEvent.Notice("已停止"))
                    else listener(AgentEvent.Failure(e.message ?: "请求模型失败"))
                    return
                }
                if (cancelled) { listener(AgentEvent.Notice("已停止")); return }
                CrashLog.i(TAG, "第 $step 步：模型返回，工具调用 ${reply.toolCalls.size} 个")

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

                    if (c.name == "generate_image") {
                        if (ok) {
                            imageFailures = 0
                        } else {
                            imageFailures++
                            if (imageFailures >= 3) {
                                val reason = runCatching { JSONObject(modelResult).optString("error") }
                                    .getOrDefault("")
                                listener(AgentEvent.Failure(
                                    "图片生成连续失败 3 次，已停止重试。" +
                                        if (reason.isNotBlank()) "原因：$reason" else ""
                                ))
                                return
                            }
                        }
                    }

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

    /**
     * 将之前保存的历史消息接续到稳定前缀之后。
     * 运行时 system 消息属于历史的一部分：保留它，才能在内容未变时避免重复追加，
     * 也才能在内容变化时把新版本追加到末尾而不破坏前缀。
     */
    private fun JSONArray.appendHistory(session: ChatSession?) {
        if (session == null) return
        val prior = runCatching { JSONArray(session.agentMessages) }.getOrNull() ?: return
        for (i in 0 until prior.length()) {
            val m = prior.optJSONObject(i) ?: continue
            put(m)
        }
    }

    /**
     * 把本轮消息写回会话；剥离图片等大体积内容并限制历史长度。
     * 第 0 条稳定前缀不持久化（每次单独重建）；运行时 system 消息保留在历史中。
     */
    private fun persistHistory(session: ChatSession, messages: JSONArray) {
        val kept = JSONArray()
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            if (i == 0 && m.optString("role") == "system") continue
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

    private fun assistantMessage(reply: LLMResponse): JSONObject = JSONObject().apply {
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

    private fun execute(call: LLMToolCall): String {
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
                        val urls = LLMService.generateImage(
                            account = imageProvider.copy(imageModel = model),
                            prompt = prompt,
                            size = args.optString("size", "1024x1024"),
                            count = args.optInt("count", 1),
                            watermark = AgentConfig.imageWatermark(ctx),
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

            "search_plugins" -> searchPlugins(args)

            "install_plugin" -> installPlugin(args)

            "list_installed_plugins" -> listInstalledPlugins()

            "set_plugin_enabled" -> setPluginEnabled(args)

            "remove_plugin" -> removePlugin(args)

            "github_status" -> githubStatus()

            "github_save_config" -> githubSaveConfig(args)

            "github_list_repos" -> githubListRepos(args)

            "github_get_repo" -> githubGetRepo(args)

            "github_list_branches" -> githubListBranches(args)

            "github_read_file" -> githubReadFile(args)

            "github_write_file" -> githubWriteFile(args)

            "github_list_commits" -> githubListCommits(args)

            "github_list_issues" -> githubListIssues(args)

            "github_create_issue" -> githubCreateIssue(args)

            "github_comment_issue" -> githubCommentIssue(args)

            "github_list_pulls" -> githubListPulls(args)

            "github_create_pull" -> githubCreatePull(args)

            "github_search_repos" -> githubSearchRepos(args)

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
        val models = LLMService.listModels(protocol, baseUrl, apiKey)
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
                existing?.supportsReasoningEffort ?: (protocol == ProviderProtocol.OPENAI &&
                    (baseUrl.contains("deepseek") || baseUrl.contains("volces") || baseUrl.contains("ark."))),
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
        CrashLog.i(TAG, "上下文过长，开始压缩历史")

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
            LLMService.chat(
                provider,
                LLMRequest(
                    model = provider.model,
                    messages = LLMCodec.openAiToMessages(prompt),
                    thinking = LLMThinking.OFF,
                    omitThinkingParam = true,
                ),
            ).content?.trim().orEmpty()
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
            put(fn("shell", "执行 shell 命令。已安装并授权 Termux 时自动走 Termux 的完整 Linux 环境（bash/python/node/git 等），否则用内置命令行以应用自身权限执行（无需 Root/Shizuku）；需要系统级权限时可提示用户切到 Shizuku/Root 模式。", JSONObject()
                .put("cmd", str("要执行的命令")), listOf("cmd")))

            // generate_image 始终声明：工具 schema 顺序与集合固定，避免因增删工具
            // 导致提供商前缀缓存整段失效；未配置生图模型时调用会返回友好错误。
            put(fn(
                "generate_image",
                "根据文字描述生成图片（文生图）。当用户要求画图、生成图片、制作海报或配图时调用，生成结果会自动展示给用户。",
                JSONObject()
                    .put("prompt", str("图片内容的详细描述，建议包含主体、风格、构图、色彩"))
                    .put("size", str("图片尺寸，如 1024x1024；不同提供商支持的尺寸可能不同，可省略"))
                    .put("count", num("生成张数，默认 1")),
                listOf("prompt"),
            ))

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
                    .put("supportsReasoningEffort", JSONObject().put("type", "boolean").put("description", "是否支持思考参数（如 reasoning_effort / reasoning），默认 false"))
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

            put(fn(
                "search_plugins",
                "扫描热门开源仓库，查找可安装的 Agent 插件（技能）。用户想扩展能力、寻找插件或让你「扫描热门插件」时调用。返回候选列表（含 repo、描述、star 数、installUrl），随后用 install_plugin 一键安装。",
                JSONObject()
                    .put("keyword", str("搜索关键词，如 代码审查、写测试、ponytail；留空返回内置精选"))
                    .put("limit", num("最多返回条数，默认 8")),
                emptyList(),
            ))

            put(fn(
                "install_plugin",
                "一键安装插件：把候选仓库中的 SKILL.md 下载并写入本机技能库。参数用 search_plugins 返回的 installUrl。",
                JSONObject().put("url", str("插件仓库地址或 installUrl")),
                listOf("url"),
            ))

            put(fn(
                "list_installed_plugins",
                "列出本机已安装的插件（技能）及其启停状态。",
                JSONObject(), emptyList(),
            ))

            put(fn(
                "set_plugin_enabled",
                "启用或停用一个已安装插件。",
                JSONObject()
                    .put("id", str("插件 id"))
                    .put("name", str("插件名称（用名称匹配，id 可省略）"))
                    .put("enabled", JSONObject().put("type", "boolean").put("description", "是否启用")),
                emptyList(),
            ))

            put(fn(
                "remove_plugin",
                "删除一个已安装插件。",
                JSONObject()
                    .put("id", str("插件 id"))
                    .put("name", str("插件名称（用名称匹配，id 可省略）")),
                emptyList(),
            ))

            // ---- GitHub 仓库接入：读取/提交文件、Issue、Pull Request ----

            put(fn(
                "github_status",
                "查看 GitHub 接入状态：是否已配置 Token、当前登录账号、默认仓库与分支。用户提到「我的 GitHub 仓库」或需要操作仓库前先调用。",
                JSONObject(), emptyList(),
            ))

            put(fn(
                "github_save_config",
                "保存 GitHub 接入配置（Personal Access Token 与可选默认仓库/分支）。当用户提供 Token 或要求接入其 GitHub 仓库时调用；保存前会先校验 Token。",
                JSONObject()
                    .put("token", str("GitHub Personal Access Token（fine-grained 或 classic，需 repo 权限）"))
                    .put("defaultRepo", str("默认仓库，形如 owner/repo，可省略"))
                    .put("defaultBranch", str("默认分支，留空使用仓库默认分支")),
                listOf("token"),
            ))

            put(fn(
                "github_list_repos",
                "列出当前 Token 可访问的仓库（含私有仓库），按最近更新排序。",
                JSONObject().put("limit", num("最多返回条数，默认 30")),
                emptyList(),
            ))

            put(fn(
                "github_get_repo",
                "查看某个仓库的概览：默认分支、是否私有、star 数、开放 Issue 数等。",
                JSONObject().put("repo", str("仓库 owner/repo，省略则用默认仓库")),
                emptyList(),
            ))

            put(fn(
                "github_list_branches",
                "列出仓库的分支。",
                JSONObject().put("repo", str("仓库 owner/repo，省略则用默认仓库")),
                emptyList(),
            ))

            put(fn(
                "github_read_file",
                "读取仓库中某个文件的内容与当前 sha（更新文件时需要 sha）。",
                JSONObject()
                    .put("repo", str("仓库 owner/repo，省略则用默认仓库"))
                    .put("path", str("文件路径，如 src/main.py"))
                    .put("ref", str("分支或 commit，省略则用默认分支")),
                listOf("path"),
            ))

            put(fn(
                "github_write_file",
                "创建或更新仓库中的文件并产生一次提交。更新已有文件时必须先 github_read_file 拿到 sha 并在参数中回传；新建文件 sha 留空。",
                JSONObject()
                    .put("repo", str("仓库 owner/repo，省略则用默认仓库"))
                    .put("path", str("文件路径"))
                    .put("content", str("文件完整内容"))
                    .put("message", str("提交信息"))
                    .put("branch", str("提交到哪个分支，省略则用默认分支"))
                    .put("sha", str("更新已有文件时的当前 sha；新建文件留空")),
                listOf("path", "content"),
            ))

            put(fn(
                "github_list_commits",
                "列出仓库的提交记录。",
                JSONObject()
                    .put("repo", str("仓库 owner/repo，省略则用默认仓库"))
                    .put("ref", str("分支或 commit，省略则用默认分支"))
                    .put("limit", num("最多返回条数，默认 20")),
                emptyList(),
            ))

            put(fn(
                "github_list_issues",
                "列出仓库的 Issue（含 PR，可按需过滤）。",
                JSONObject()
                    .put("repo", str("仓库 owner/repo，省略则用默认仓库"))
                    .put("state", str("open / closed / all，默认 open"))
                    .put("limit", num("最多返回条数，默认 20")),
                emptyList(),
            ))

            put(fn(
                "github_create_issue",
                "在仓库中新建一个 Issue。",
                JSONObject()
                    .put("repo", str("仓库 owner/repo，省略则用默认仓库"))
                    .put("title", str("Issue 标题"))
                    .put("body", str("Issue 正文，支持 Markdown")),
                listOf("title"),
            ))

            put(fn(
                "github_comment_issue",
                "给某个 Issue 或 PR 添加评论。",
                JSONObject()
                    .put("repo", str("仓库 owner/repo，省略则用默认仓库"))
                    .put("number", num("Issue/PR 编号"))
                    .put("body", str("评论内容")),
                listOf("number", "body"),
            ))

            put(fn(
                "github_list_pulls",
                "列出仓库的 Pull Request。",
                JSONObject()
                    .put("repo", str("仓库 owner/repo，省略则用默认仓库"))
                    .put("state", str("open / closed / all，默认 open"))
                    .put("limit", num("最多返回条数，默认 20")),
                emptyList(),
            ))

            put(fn(
                "github_create_pull",
                "基于已有分支创建 Pull Request。",
                JSONObject()
                    .put("repo", str("仓库 owner/repo，省略则用默认仓库"))
                    .put("title", str("PR 标题"))
                    .put("head", str("来源分支"))
                    .put("base", str("目标分支，如 main"))
                    .put("body", str("PR 描述，支持 Markdown")),
                listOf("title", "head", "base"),
            ))

            put(fn(
                "github_search_repos",
                "在 GitHub 上按关键词搜索公开仓库（按 star 排序），用于发现可参考或可安装的项目。",
                JSONObject()
                    .put("query", str("搜索关键词"))
                    .put("limit", num("最多返回条数，默认 10")),
                listOf("query"),
            ))
        }
    }

    // ==================== 插件市场 ====================

    private fun searchPlugins(args: JSONObject): String {
        val keyword = args.optString("keyword")
        val limit = args.optInt("limit", 8)
        val list = PluginCatalog.search(keyword, limit, GitHubConfig.token(ctx))
        return JSONObject().apply {
            put("ok", true)
            put("count", list.size)
            put("plugins", JSONArray().apply { list.forEach { put(it.toJson()) } })
        }.toString()
    }

    private fun installPlugin(args: JSONObject): String {
        val url = args.optString("url").trim()
        if (url.isBlank()) return err("缺少 url")
        return try {
            val name = PluginCatalog.install(ctx, url)
            JSONObject().put("ok", true).put("installed", name).toString()
        } catch (e: Exception) {
            err(e.message ?: "安装失败")
        }
    }

    private fun listInstalledPlugins(): String {
        val list = SkillStore.all(ctx)
        return JSONObject().apply {
            put("ok", true)
            put("count", list.size)
            put("plugins", JSONArray().apply {
                list.forEach { s ->
                    put(JSONObject()
                        .put("id", s.id)
                        .put("name", s.name)
                        .put("description", s.description)
                        .put("enabled", s.enabled)
                        .put("source", s.source.ifBlank { "内置" }))
                }
            })
        }.toString()
    }

    private fun resolveSkill(args: JSONObject): Skill? {
        val id = args.optString("id")
        val name = args.optString("name")
        val all = SkillStore.all(ctx)
        return all.firstOrNull { id.isNotBlank() && it.id == id }
            ?: all.firstOrNull { name.isNotBlank() && it.name == name }
            ?: all.firstOrNull { name.isNotBlank() && it.name.contains(name) }
    }

    private fun setPluginEnabled(args: JSONObject): String {
        val skill = resolveSkill(args) ?: return err("未找到该插件")
        val enabled = if (args.has("enabled")) args.optBoolean("enabled") else true
        SkillStore.setEnabled(ctx, skill.id, enabled)
        return JSONObject().put("ok", true).put("name", skill.name).put("enabled", enabled).toString()
    }

    private fun removePlugin(args: JSONObject): String {
        val skill = resolveSkill(args) ?: return err("未找到该插件")
        SkillStore.remove(ctx, skill.id)
        return JSONObject().put("ok", true).put("removed", skill.name).toString()
    }

    // ==================== GitHub 仓库 ====================

    private fun githubToken(): String = GitHubConfig.token(ctx)

    private fun resolveRepo(args: JSONObject): String {
        val repo = args.optString("repo").trim()
        return repo.ifBlank { GitHubConfig.defaultRepo(ctx) }
    }

    private fun resolveBranch(args: JSONObject): String {
        val branch = args.optString("branch").takeIf { args.has("branch") }
            ?: args.optString("ref").takeIf { args.has("ref") }
        return branch?.trim().orEmpty().ifBlank { GitHubConfig.defaultBranch(ctx) }
    }

    private fun requireToken(): String? = if (githubToken().isBlank()) {
        err("尚未接入 GitHub，请先在「设置 → GitHub」填写 Personal Access Token，或把 Token 发给我代为保存。")
    } else {
        null
    }

    private fun githubStatus(): String {
        val token = githubToken()
        val out = JSONObject().apply {
            put("ok", true)
            put("configured", token.isNotBlank())
            put("defaultRepo", GitHubConfig.defaultRepo(ctx))
            put("defaultBranch", GitHubConfig.defaultBranch(ctx))
        }
        if (token.isBlank()) return out.put("hint", "未配置 Token。可让用户提供 Token 后调用 github_save_config。").toString()
        return try {
            val user = GitHubClient.whoami(token)
            out.put("login", user.optString("login"))
                .put("name", user.optString("name"))
                .put("publicRepos", user.optInt("public_repos"))
                .put("totalRepos", user.optInt("total_private_repos") + user.optInt("public_repos"))
                .put("tokenValid", true)
            out.toString()
        } catch (e: Exception) {
            out.put("tokenValid", false).put("error", e.message ?: "Token 校验失败").toString()
        }
    }

    private fun githubSaveConfig(args: JSONObject): String {
        val token = args.optString("token").trim()
        if (token.isBlank()) return err("缺少 token")
        return try {
            val user = GitHubClient.whoami(token)
            GitHubConfig.setToken(ctx, token)
            if (args.has("defaultRepo")) GitHubConfig.setDefaultRepo(ctx, args.optString("defaultRepo"))
            if (args.has("defaultBranch")) GitHubConfig.setDefaultBranch(ctx, args.optString("defaultBranch"))
            JSONObject().put("ok", true)
                .put("login", user.optString("login"))
                .put("defaultRepo", GitHubConfig.defaultRepo(ctx))
                .put("defaultBranch", GitHubConfig.defaultBranch(ctx))
                .toString()
        } catch (e: Exception) {
            err(e.message ?: "Token 校验失败")
        }
    }

    private fun githubListRepos(args: JSONObject): String {
        requireToken()?.let { return it }
        return try {
            val arr = GitHubClient.listRepos(githubToken(), args.optInt("limit", 30))
            JSONObject().put("ok", true).put("count", arr.length()).put("repos", JSONArray().apply {
                for (i in 0 until arr.length()) {
                    val r = arr.getJSONObject(i)
                    put(JSONObject()
                        .put("fullName", r.optString("full_name"))
                        .put("private", r.optBoolean("private"))
                        .put("defaultBranch", r.optString("default_branch"))
                        .put("description", r.optString("description"))
                        .put("stars", r.optInt("stargazers_count"))
                        .put("updatedAt", r.optString("updated_at")))
                }
            }).toString()
        } catch (e: Exception) {
            err(e.message ?: "获取仓库列表失败")
        }
    }

    private fun githubGetRepo(args: JSONObject): String {
        requireToken()?.let { return it }
        val repo = resolveRepo(args)
        if (repo.isBlank()) return err("请指定仓库，或先设置默认仓库")
        return try {
            val r = GitHubClient.getRepo(githubToken(), repo)
            JSONObject().put("ok", true)
                .put("fullName", r.optString("full_name"))
                .put("private", r.optBoolean("private"))
                .put("defaultBranch", r.optString("default_branch"))
                .put("description", r.optString("description"))
                .put("stars", r.optInt("stargazers_count"))
                .put("forks", r.optInt("forks_count"))
                .put("openIssues", r.optInt("open_issues_count"))
                .put("htmlUrl", r.optString("html_url"))
                .toString()
        } catch (e: Exception) {
            err(e.message ?: "获取仓库失败")
        }
    }

    private fun githubListBranches(args: JSONObject): String {
        requireToken()?.let { return it }
        val repo = resolveRepo(args)
        if (repo.isBlank()) return err("请指定仓库，或先设置默认仓库")
        return try {
            val arr = GitHubClient.listBranches(githubToken(), repo)
            JSONObject().put("ok", true).put("branches", JSONArray().apply {
                for (i in 0 until arr.length()) put(arr.getJSONObject(i).optString("name"))
            }).toString()
        } catch (e: Exception) {
            err(e.message ?: "获取分支失败")
        }
    }

    private fun githubReadFile(args: JSONObject): String {
        requireToken()?.let { return it }
        val repo = resolveRepo(args)
        val path = args.optString("path").trim()
        if (repo.isBlank()) return err("请指定仓库，或先设置默认仓库")
        if (path.isBlank()) return err("缺少 path")
        return try {
            val file = GitHubClient.getFile(githubToken(), repo, path, resolveBranch(args))
            val encoded = file.optString("content").replace("\n", "")
            val decoded = runCatching {
                String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT), Charsets.UTF_8)
            }.getOrDefault("")
            JSONObject().put("ok", true)
                .put("path", file.optString("path"))
                .put("sha", file.optString("sha"))
                .put("size", file.optInt("size"))
                .put("content", decoded)
                .toString()
        } catch (e: Exception) {
            err(e.message ?: "读取文件失败")
        }
    }

    private fun githubWriteFile(args: JSONObject): String {
        requireToken()?.let { return it }
        val repo = resolveRepo(args)
        val path = args.optString("path").trim()
        if (repo.isBlank()) return err("请指定仓库，或先设置默认仓库")
        if (path.isBlank()) return err("缺少 path")
        val content = args.optString("content")
        val branch = resolveBranch(args)
        return try {
            // 未提供 sha 时自动探测：文件已存在则用其 sha 更新，否则按新建提交。
            var sha = args.optString("sha").trim()
            if (sha.isBlank()) {
                sha = runCatching { GitHubClient.getFile(githubToken(), repo, path, branch).optString("sha") }
                    .getOrDefault("")
            }
            val res = GitHubClient.putFile(
                token = githubToken(),
                repo = repo,
                path = path,
                content = content,
                message = args.optString("message"),
                branch = branch,
                sha = sha,
            )
            val commit = res.optJSONObject("commit")
            JSONObject().put("ok", true)
                .put("path", path)
                .put("updated", sha.isNotBlank())
                .put("commit", commit?.optString("sha").orEmpty())
                .put("htmlUrl", commit?.optString("html_url").orEmpty())
                .toString()
        } catch (e: Exception) {
            err(e.message ?: "提交失败")
        }
    }

    private fun githubListCommits(args: JSONObject): String {
        requireToken()?.let { return it }
        val repo = resolveRepo(args)
        if (repo.isBlank()) return err("请指定仓库，或先设置默认仓库")
        return try {
            val arr = GitHubClient.listCommits(githubToken(), repo, resolveBranch(args), args.optInt("limit", 20))
            JSONObject().put("ok", true).put("commits", JSONArray().apply {
                for (i in 0 until arr.length()) {
                    val c = arr.getJSONObject(i)
                    put(JSONObject()
                        .put("sha", c.optString("sha").take(8))
                        .put("message", c.optJSONObject("commit")?.optString("message")?.lineSequence()?.firstOrNull().orEmpty())
                        .put("author", c.optJSONObject("commit")?.optJSONObject("author")?.optString("name").orEmpty())
                        .put("date", c.optJSONObject("commit")?.optJSONObject("author")?.optString("date").orEmpty()))
                }
            }).toString()
        } catch (e: Exception) {
            err(e.message ?: "获取提交记录失败")
        }
    }

    private fun githubListIssues(args: JSONObject): String {
        requireToken()?.let { return it }
        val repo = resolveRepo(args)
        if (repo.isBlank()) return err("请指定仓库，或先设置默认仓库")
        return try {
            val arr = GitHubClient.listIssues(githubToken(), repo, args.optString("state", "open"), args.optInt("limit", 20))
            JSONObject().put("ok", true).put("issues", JSONArray().apply {
                for (i in 0 until arr.length()) {
                    val it = arr.getJSONObject(i)
                    put(JSONObject()
                        .put("number", it.optInt("number"))
                        .put("title", it.optString("title"))
                        .put("state", it.optString("state"))
                        .put("isPull", it.has("pull_request"))
                        .put("user", it.optJSONObject("user")?.optString("login").orEmpty()))
                }
            }).toString()
        } catch (e: Exception) {
            err(e.message ?: "获取 Issue 失败")
        }
    }

    private fun githubCreateIssue(args: JSONObject): String {
        requireToken()?.let { return it }
        val repo = resolveRepo(args)
        val title = args.optString("title").trim()
        if (repo.isBlank()) return err("请指定仓库，或先设置默认仓库")
        if (title.isBlank()) return err("缺少 title")
        return try {
            val res = GitHubClient.createIssue(githubToken(), repo, title, args.optString("body"))
            JSONObject().put("ok", true)
                .put("number", res.optInt("number"))
                .put("htmlUrl", res.optString("html_url"))
                .toString()
        } catch (e: Exception) {
            err(e.message ?: "创建 Issue 失败")
        }
    }

    private fun githubCommentIssue(args: JSONObject): String {
        requireToken()?.let { return it }
        val repo = resolveRepo(args)
        val number = args.optInt("number", 0)
        val body = args.optString("body")
        if (repo.isBlank()) return err("请指定仓库，或先设置默认仓库")
        if (number <= 0) return err("缺少 number")
        if (body.isBlank()) return err("缺少 body")
        return try {
            val res = GitHubClient.commentIssue(githubToken(), repo, number, body)
            JSONObject().put("ok", true).put("htmlUrl", res.optString("html_url")).toString()
        } catch (e: Exception) {
            err(e.message ?: "评论失败")
        }
    }

    private fun githubListPulls(args: JSONObject): String {
        requireToken()?.let { return it }
        val repo = resolveRepo(args)
        if (repo.isBlank()) return err("请指定仓库，或先设置默认仓库")
        return try {
            val arr = GitHubClient.listPulls(githubToken(), repo, args.optString("state", "open"), args.optInt("limit", 20))
            JSONObject().put("ok", true).put("pulls", JSONArray().apply {
                for (i in 0 until arr.length()) {
                    val p = arr.getJSONObject(i)
                    put(JSONObject()
                        .put("number", p.optInt("number"))
                        .put("title", p.optString("title"))
                        .put("state", p.optString("state"))
                        .put("head", p.optJSONObject("head")?.optString("ref").orEmpty())
                        .put("base", p.optJSONObject("base")?.optString("ref").orEmpty()))
                }
            }).toString()
        } catch (e: Exception) {
            err(e.message ?: "获取 Pull Request 失败")
        }
    }

    private fun githubCreatePull(args: JSONObject): String {
        requireToken()?.let { return it }
        val repo = resolveRepo(args)
        val title = args.optString("title").trim()
        val head = args.optString("head").trim()
        val base = args.optString("base").trim()
        if (repo.isBlank()) return err("请指定仓库，或先设置默认仓库")
        if (title.isBlank() || head.isBlank() || base.isBlank()) return err("缺少 title/head/base")
        return try {
            val res = GitHubClient.createPull(githubToken(), repo, title, head, base, args.optString("body"))
            JSONObject().put("ok", true)
                .put("number", res.optInt("number"))
                .put("htmlUrl", res.optString("html_url"))
                .toString()
        } catch (e: Exception) {
            err(e.message ?: "创建 Pull Request 失败")
        }
    }

    private fun githubSearchRepos(args: JSONObject): String {
        val query = args.optString("query").trim()
        if (query.isBlank()) return err("缺少 query")
        return try {
            val arr = GitHubClient.searchRepos(githubToken(), query, args.optInt("limit", 10))
            JSONObject().put("ok", true).put("repos", JSONArray().apply {
                for (i in 0 until arr.length()) {
                    val r = arr.getJSONObject(i)
                    put(JSONObject()
                        .put("fullName", r.optString("full_name"))
                        .put("description", r.optString("description"))
                        .put("stars", r.optInt("stargazers_count"))
                        .put("htmlUrl", r.optString("html_url")))
                }
            }).toString()
        } catch (e: Exception) {
            err(e.message ?: "搜索失败")
        }
    }

}

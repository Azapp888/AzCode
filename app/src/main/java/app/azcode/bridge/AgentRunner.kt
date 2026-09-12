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

    /** 中性系统提示，如达到步数上限。 */
    data class Notice(val text: String) : AgentEvent

    /** 失败提示。 */
    data class Failure(val message: String) : AgentEvent
}

/**
 * 设备端 Agent 决策循环：按需读取屏幕 → 交 DeepSeek 决策 → 直接调用本机无障碍/Shizuku 执行 → 回灌结果。
 *
 * 会话续接：每个任务的上下文（历史消息）保存在 [ChatSession.agentMessages] 中，
 * 下一轮请求会在系统提示词之后接续这些历史，从而让同一会话里的多轮对话保持连贯。
 * 系统提示词不持久化，每次根据最新的技能/记忆重新构建。
 */
class AgentRunner(
    private val ctx: Context,
    private val listener: (AgentEvent) -> Unit,
) {
    @Volatile private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    fun run(task: String, attachments: List<AttachmentReader.Prepared> = emptyList(), session: ChatSession? = null) {
        val apiKey = AgentConfig.apiKey(ctx)
        val baseUrl = AgentConfig.baseUrl(ctx)
        val model = AgentConfig.model(ctx)
        val maxSteps = AgentConfig.maxSteps(ctx)

        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", buildSystemPrompt()))
            appendHistory(session)
            put(JSONObject().put("role", "user").put("content", DeepSeekClient.buildUserContent(task, attachments)))
        }
        val tools = buildTools()
        val depth = AgentConfig.thinkingDepth(ctx)
        val recentSigs = ArrayDeque<String>()

        try {
            var step = 0
            while (maxSteps <= 0 || step < maxSteps) {
                step++
                if (cancelled) { listener(AgentEvent.Notice("已停止")); return }
                listener(AgentEvent.Thinking)

                val reply = try {
                    DeepSeekClient.chat(baseUrl, apiKey, model, messages, tools, depth.effort)
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

                // 重复检测：在最近 WINDOW 步内出现 3 次完全相同的输出，判定为原地打转并停止。
                val sig = buildString {
                    append(reply.content?.trim().orEmpty())
                    reply.toolCalls.forEach { append('|').append(it.name).append('(').append(it.arguments).append(')') }
                }
                recentSigs.addLast(sig)
                if (recentSigs.size > LOOP_WINDOW) recentSigs.removeFirst()
                if (recentSigs.count { it == sig } >= LOOP_REPEAT) {
                    listener(AgentEvent.Notice("检测到模型重复输出相同内容，已自动停止任务"))
                    return
                }

                for (c in reply.toolCalls) {
                    if (cancelled) { listener(AgentEvent.Notice("已停止")); return }
                    listener(AgentEvent.ToolStart(c.name, c.arguments))

                    val result = execute(c)
                    val ok = runCatching { JSONObject(result).optBoolean("ok", false) }.getOrDefault(false)
                    listener(AgentEvent.ToolResult(c.name, ok, result))

                    messages.put(JSONObject().apply {
                        put("role", "tool")
                        put("tool_call_id", c.id)
                        put("content", result)
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

            "finish" -> JSONObject().put("ok", true).put("summary", args.optString("summary")).toString()

            else -> err("未知工具 ${call.name}")
            }
        } catch (e: Exception) {
            err(e.message ?: "执行异常")
        }
    }

    private fun ok() = """{"ok":true}"""

    private fun err(message: String) = JSONObject().put("ok", false).put("error", message).toString()

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
            put(fn("finish", "任务结束并给出总结。", JSONObject()
                .put("summary", str("结果总结")), listOf("summary")))
        }
    }

    // ==================== 系统提示词 ====================

    private fun buildSystemPrompt(): String {
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

        return sb.toString()
    }

    private companion object {
        /** 重复检测滑动窗口步数。 */
        const val LOOP_WINDOW = 10

        /** 同一输出在窗口内出现次数达到该值即判定为重复。 */
        const val LOOP_REPEAT = 3
    }
}

package app.azcode.bridge.llm.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 内部统一格式与「OpenAI 兼容 JSON」之间的编解码工具。
 * 内部格式即 OpenAI 语义，因此这里也是各适配器复用最多的转换层。
 */
object LLMCodec {

    // ==================== 消息 ====================

    fun LLMMessage.toJson(): JSONObject = JSONObject().apply {
        put("role", role)
        put("content", content ?: JSONObject.NULL)
        if (toolCalls.isNotEmpty()) {
            put("tool_calls", JSONArray().apply {
                toolCalls.forEach { c -> put(c.toJson()) }
            })
        }
        toolCallId?.let { put("tool_call_id", it) }
    }

    fun LLMToolCall.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", "function")
        put("function", JSONObject().put("name", name).put("arguments", arguments))
    }

    fun LLMTool.toJson(): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject()
            .put("name", name)
            .put("description", description)
            .put("parameters", parameters))
    }

    fun messagesJson(messages: List<LLMMessage>): JSONArray =
        JSONArray().apply { messages.forEach { put(it.toJson()) } }

    fun toolsJson(tools: List<LLMTool>): JSONArray =
        JSONArray().apply { tools.forEach { put(it.toJson()) } }

    fun openAiToMessages(arr: JSONArray?): List<LLMMessage> {
        if (arr == null) return emptyList()
        val out = ArrayList<LLMMessage>(arr.length())
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val content: Any? = when (val c = m.opt("content")) {
                null, JSONObject.NULL -> null
                else -> c
            }
            val calls = m.optJSONArray("tool_calls")?.let { toolCalls ->
                (0 until toolCalls.length()).mapNotNull { j ->
                    val t = toolCalls.optJSONObject(j) ?: return@mapNotNull null
                    val fn = t.optJSONObject("function") ?: return@mapNotNull null
                    LLMToolCall(
                        id = t.optString("id"),
                        name = fn.optString("name"),
                        arguments = fn.optString("arguments", "{}"),
                    )
                }
            } ?: emptyList()
            out.add(
                LLMMessage(
                    role = m.optString("role"),
                    content = content,
                    toolCalls = calls,
                    toolCallId = m.optString("tool_call_id").takeIf { it.isNotBlank() },
                )
            )
        }
        return out
    }

    fun openAiToTools(arr: JSONArray?): List<LLMTool> {
        if (arr == null) return emptyList()
        val out = ArrayList<LLMTool>(arr.length())
        for (i in 0 until arr.length()) {
            val fn = arr.optJSONObject(i)?.optJSONObject("function") ?: continue
            out.add(
                LLMTool(
                    name = fn.optString("name"),
                    description = fn.optString("description"),
                    parameters = fn.optJSONObject("parameters") ?: JSONObject(),
                )
            )
        }
        return out
    }

    // ==================== 响应 ====================

    /** 解析 OpenAI 兼容的响应体（也被响应结构一致的厂商复用）。 */
    fun parseOpenAiResponse(raw: JSONObject): LLMResponse {
        val choice = raw.optJSONArray("choices")?.optJSONObject(0)
            ?: throw LLMException(LLMErrorCode.UNKNOWN, "模型响应缺少 choices：${raw.toString().take(200)}")
        val message = choice.optJSONObject("message") ?: JSONObject()
        val content = if (message.isNull("content")) null else message.optString("content").ifEmpty { null }
        val reasoning = when {
            message.has("reasoning_content") -> message.optString("reasoning_content").ifBlank { null }
            message.has("reasoning") -> message.optString("reasoning").ifBlank { null }
            else -> null
        }
        val calls = message.optJSONArray("tool_calls")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val c = arr.optJSONObject(i) ?: return@mapNotNull null
                val fn = c.optJSONObject("function") ?: return@mapNotNull null
                LLMToolCall(
                    id = c.optString("id"),
                    name = fn.optString("name"),
                    arguments = fn.optString("arguments", "{}"),
                )
            }
        } ?: emptyList()
        return LLMResponse(
            content = content,
            reasoning = reasoning,
            finishReason = choice.optString("finish_reason", "stop"),
            usage = parseUsage(raw.optJSONObject("usage")),
            toolCalls = calls,
        )
    }

    fun parseUsage(usage: JSONObject?): LLMUsage {
        if (usage == null) return LLMUsage()
        return LLMUsage(
            promptTokens = usage.optInt("prompt_tokens", 0),
            completionTokens = usage.optInt("completion_tokens", 0),
            totalTokens = usage.optInt("total_tokens", 0),
        )
    }
}

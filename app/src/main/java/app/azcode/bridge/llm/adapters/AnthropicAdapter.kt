package app.azcode.bridge.llm.adapters

import app.azcode.bridge.ProviderAccount
import app.azcode.bridge.ProviderProtocol
import app.azcode.bridge.llm.core.BaseAdapter
import app.azcode.bridge.llm.core.LLMErrorCode
import app.azcode.bridge.llm.core.LLMCodec
import app.azcode.bridge.llm.core.LLMException
import app.azcode.bridge.llm.core.LLMRequest
import app.azcode.bridge.llm.core.LLMResponse
import app.azcode.bridge.llm.core.LLMToolCall
import app.azcode.bridge.llm.core.LLMTransport
import app.azcode.bridge.llm.core.LLMUsage
import org.json.JSONArray
import org.json.JSONObject

/**
 * Anthropic Messages 协议适配器。内部统一消息（OpenAI 语义）在 [translateRequest]
 * 中转换为 Anthropic 的 system + content blocks 结构。
 */
class AnthropicAdapter : BaseAdapter() {

    override val id: String = "anthropic"
    override val label: String = "Anthropic Claude"
    override val protocol: ProviderProtocol = ProviderProtocol.ANTHROPIC

    override fun endpoint(account: ProviderAccount, request: LLMRequest): String =
        account.baseUrl.trimEnd('/') + "/messages"

    override fun headers(account: ProviderAccount): Map<String, String> = mapOf(
        "x-api-key" to account.apiKey,
        "anthropic-version" to "2023-06-01",
    )

    override fun translateRequest(request: LLMRequest): JSONObject {
        val (system, converted) = convertToAnthropic(LLMCodec.messagesJson(request.messages))
        return JSONObject().apply {
            put("model", request.model)
            put("max_tokens", 8192)
            request.temperature?.let { put("temperature", it) }
            if (system.isNotBlank()) put("system", system)
            put("messages", converted)
            if (request.tools.isNotEmpty()) put("tools", anthropicTools(LLMCodec.toolsJson(request.tools)))
        }
    }

    override fun translateResponse(raw: JSONObject): LLMResponse {
        val content = raw.optJSONArray("content") ?: JSONArray()
        val text = StringBuilder()
        val reasoning = StringBuilder()
        val calls = mutableListOf<LLMToolCall>()
        for (i in 0 until content.length()) {
            val b = content.optJSONObject(i) ?: continue
            when (b.optString("type")) {
                "text" -> text.append(b.optString("text"))
                "thinking" -> reasoning.append(b.optString("thinking"))
                "tool_use" -> calls.add(
                    LLMToolCall(
                        id = b.optString("id"),
                        name = b.optString("name"),
                        arguments = (b.optJSONObject("input") ?: JSONObject()).toString(),
                    )
                )
            }
        }
        return LLMResponse(
            content = text.toString().ifBlank { null },
            reasoning = reasoning.toString().ifBlank { null },
            finishReason = raw.optString("stop_reason", "stop"),
            usage = raw.optJSONObject("usage")?.let {
                LLMUsage(
                    promptTokens = it.optInt("input_tokens", 0),
                    completionTokens = it.optInt("output_tokens", 0),
                    totalTokens = it.optInt("input_tokens", 0) + it.optInt("output_tokens", 0),
                )
            } ?: LLMUsage(),
            toolCalls = calls,
        )
    }

    override fun listModels(baseUrl: String, apiKey: String): List<String> {
        val (code, text) = LLMTransport.get(
            "${baseUrl.trimEnd('/')}/models",
            mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01"),
        )
        if (code !in 200..299) {
            throw LLMException(
                LLMErrorCode.fromHttp(code),
                "拉取模型列表 HTTP $code：${text.take(300)}",
                httpStatus = code,
            )
        }
        val arr = JSONObject(text).optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }
        }
    }

    // ==================== 格式转换 ====================

    private fun convertToAnthropic(messages: JSONArray): Pair<String, JSONArray> {
        val system = StringBuilder()
        val out = JSONArray()

        fun append(role: String, blocks: JSONArray) {
            if (out.length() > 0) {
                val last = out.getJSONObject(out.length() - 1)
                if (last.optString("role") == role) {
                    val arr = last.getJSONArray("content")
                    for (i in 0 until blocks.length()) arr.put(blocks.getJSONObject(i))
                    return
                }
            }
            out.put(JSONObject().put("role", role).put("content", blocks))
        }

        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            when (m.optString("role")) {
                "system" -> {
                    if (system.isNotEmpty()) system.append("\n\n")
                    system.append(m.optString("content"))
                }
                "user" -> append("user", anthropicUserBlocks(m.opt("content")))
                "assistant" -> append("assistant", anthropicAssistantBlocks(m))
                "tool" -> append("user", JSONArray().put(JSONObject().apply {
                    put("type", "tool_result")
                    put("tool_use_id", m.optString("tool_call_id"))
                    put("content", m.optString("content"))
                }))
            }
        }
        return system.toString() to out
    }

    private fun anthropicUserBlocks(content: Any?): JSONArray {
        val blocks = JSONArray()
        when (content) {
            is String -> blocks.put(JSONObject().put("type", "text").put("text", content))
            is JSONArray -> for (i in 0 until content.length()) {
                val part = content.optJSONObject(i) ?: continue
                when (part.optString("type")) {
                    "text" -> blocks.put(JSONObject().put("type", "text").put("text", part.optString("text")))
                    "image_url" -> {
                        val url = part.optJSONObject("image_url")?.optString("url") ?: continue
                        anthropicImageBlock(url)?.let { blocks.put(it) }
                    }
                }
            }
            else -> content?.let { blocks.put(JSONObject().put("type", "text").put("text", it.toString())) }
        }
        return blocks
    }

    private fun anthropicAssistantBlocks(m: JSONObject): JSONArray {
        val blocks = JSONArray()
        if (!m.isNull("content")) {
            val c = m.optString("content")
            if (c.isNotBlank()) blocks.put(JSONObject().put("type", "text").put("text", c))
        }
        m.optJSONArray("tool_calls")?.let { arr ->
            for (i in 0 until arr.length()) {
                val fn = arr.getJSONObject(i).optJSONObject("function") ?: continue
                val args = runCatching { JSONObject(fn.optString("arguments", "{}")) }.getOrElse { JSONObject() }
                blocks.put(JSONObject().apply {
                    put("type", "tool_use")
                    put("id", arr.getJSONObject(i).optString("id"))
                    put("name", fn.optString("name"))
                    put("input", args)
                })
            }
        }
        return blocks
    }

    private fun anthropicImageBlock(url: String): JSONObject? {
        if (url.startsWith("data:")) {
            val idx = url.indexOf("base64,")
            if (idx < 0) return null
            val mime = url.substring(5, idx).trimEnd(';').ifBlank { "image/png" }
            val data = url.substring(idx + 7)
            return JSONObject().put("type", "image").put("source", JSONObject().apply {
                put("type", "base64")
                put("media_type", mime)
                put("data", data)
            })
        }
        if (url.startsWith("http")) {
            return JSONObject().put("type", "image").put("source", JSONObject().apply {
                put("type", "url")
                put("url", url)
            })
        }
        return null
    }

    private fun anthropicTools(tools: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until tools.length()) {
            val fn = tools.optJSONObject(i)?.optJSONObject("function") ?: continue
            out.put(JSONObject().apply {
                put("name", fn.optString("name"))
                put("description", fn.optString("description"))
                put("input_schema", fn.optJSONObject("parameters") ?: JSONObject())
            })
        }
        return out
    }
}

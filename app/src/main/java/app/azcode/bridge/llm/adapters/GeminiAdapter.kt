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
import java.util.UUID

/**
 * Google Gemini 适配器。内部统一消息（OpenAI 语义）在 [translateRequest] 中转换为
 * Gemini 的 systemInstruction + contents/parts 结构；工具调用经 functionDeclarations 声明。
 */
class GeminiAdapter : BaseAdapter() {

    override val id: String = "gemini"
    override val label: String = "Google Gemini"
    override val protocol: ProviderProtocol = ProviderProtocol.GEMINI

    override fun endpoint(account: ProviderAccount, request: LLMRequest): String =
        account.baseUrl.trimEnd('/') + "/models/${request.model}:generateContent?key=${account.apiKey}"

    override fun headers(account: ProviderAccount): Map<String, String> = emptyMap()

    override fun translateRequest(request: LLMRequest): JSONObject {
        val (system, contents) = convertToGemini(LLMCodec.messagesJson(request.messages))
        return JSONObject().apply {
            if (system.isNotBlank()) {
                put("systemInstruction", JSONObject().put(
                    "parts", JSONArray().put(JSONObject().put("text", system))
                ))
            }
            put("contents", contents)
            if (request.tools.isNotEmpty()) put("tools", geminiTools(LLMCodec.toolsJson(request.tools)))
            put("generationConfig", JSONObject().put("temperature", request.temperature ?: 0.2))
        }
    }

    override fun translateResponse(raw: JSONObject): LLMResponse {
        val candidate = raw.optJSONArray("candidates")?.optJSONObject(0)
        val parts = candidate?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        val sb = StringBuilder()
        val calls = mutableListOf<LLMToolCall>()
        for (i in 0 until parts.length()) {
            val p = parts.optJSONObject(i) ?: continue
            if (p.has("text")) sb.append(p.optString("text"))
            p.optJSONObject("functionCall")?.let { fc ->
                calls.add(
                    LLMToolCall(
                        id = "gemini_${UUID.randomUUID()}",
                        name = fc.optString("name"),
                        arguments = (fc.optJSONObject("args") ?: JSONObject()).toString(),
                    )
                )
            }
        }
        return LLMResponse(
            content = sb.toString().ifBlank { null },
            finishReason = candidate?.optString("finishReason", "stop")?.lowercase() ?: "stop",
            usage = raw.optJSONObject("usageMetadata")?.let {
                LLMUsage(
                    promptTokens = it.optInt("promptTokenCount", 0),
                    completionTokens = it.optInt("candidatesTokenCount", 0),
                    totalTokens = it.optInt("totalTokenCount", 0),
                )
            } ?: LLMUsage(),
            toolCalls = calls,
        )
    }

    override fun listModels(baseUrl: String, apiKey: String): List<String> {
        val (code, text) = LLMTransport.get(
            "${baseUrl.trimEnd('/')}/models?key=$apiKey",
            emptyMap(),
        )
        if (code !in 200..299) {
            throw LLMException(
                LLMErrorCode.fromHttp(code),
                "拉取模型列表 HTTP $code：${text.take(300)}",
                httpStatus = code,
            )
        }
        val arr = JSONObject(text).optJSONArray("models") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.optString("name")?.removePrefix("models/")?.takeIf { it.isNotBlank() }
        }
    }

    // ==================== 格式转换 ====================

    private fun convertToGemini(messages: JSONArray): Pair<String, JSONArray> {
        val system = StringBuilder()
        val contents = JSONArray()
        val nameById = HashMap<String, String>()

        fun append(role: String, parts: JSONArray) {
            if (parts.length() == 0) return
            if (contents.length() > 0) {
                val last = contents.getJSONObject(contents.length() - 1)
                if (last.optString("role") == role) {
                    val arr = last.getJSONArray("parts")
                    for (i in 0 until parts.length()) arr.put(parts.getJSONObject(i))
                    return
                }
            }
            contents.put(JSONObject().put("role", role).put("parts", parts))
        }

        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            when (m.optString("role")) {
                "system" -> {
                    if (system.isNotEmpty()) system.append("\n\n")
                    system.append(m.optString("content"))
                }
                "user" -> append("user", geminiUserParts(m.opt("content")))
                "assistant" -> {
                    val parts = JSONArray()
                    if (!m.isNull("content")) {
                        val c = m.optString("content")
                        if (c.isNotBlank()) parts.put(JSONObject().put("text", c))
                    }
                    m.optJSONArray("tool_calls")?.let { arr ->
                        for (j in 0 until arr.length()) {
                            val call = arr.getJSONObject(j)
                            val fn = call.optJSONObject("function") ?: continue
                            val name = fn.optString("name")
                            nameById[call.optString("id")] = name
                            val args = runCatching { JSONObject(fn.optString("arguments", "{}")) }
                                .getOrElse { JSONObject() }
                            parts.put(JSONObject().put("functionCall", JSONObject()
                                .put("name", name).put("args", args)))
                        }
                    }
                    append("model", parts)
                }
                "tool" -> {
                    val name = nameById[m.optString("tool_call_id")] ?: "tool"
                    val response = JSONObject().put("result", m.optString("content"))
                    append("user", JSONArray().put(JSONObject().put(
                        "functionResponse", JSONObject().put("name", name).put("response", response)
                    )))
                }
            }
        }
        return system.toString() to contents
    }

    private fun geminiUserParts(content: Any?): JSONArray {
        val parts = JSONArray()
        when (content) {
            is String -> parts.put(JSONObject().put("text", content))
            is JSONArray -> for (i in 0 until content.length()) {
                val part = content.optJSONObject(i) ?: continue
                when (part.optString("type")) {
                    "text" -> parts.put(JSONObject().put("text", part.optString("text")))
                    "image_url" -> {
                        val url = part.optJSONObject("image_url")?.optString("url") ?: continue
                        val idx = url.indexOf("base64,")
                        if (url.startsWith("data:") && idx >= 0) {
                            val mime = url.substring(5, idx).trimEnd(';').ifBlank { "image/png" }
                            parts.put(JSONObject().put("inlineData", JSONObject()
                                .put("mimeType", mime)
                                .put("data", url.substring(idx + 7))))
                        }
                    }
                    "input_audio" -> {
                        val audio = part.optJSONObject("input_audio") ?: continue
                        val data = audio.optString("data")
                        if (data.isNotBlank()) {
                            parts.put(JSONObject().put("inlineData", JSONObject()
                                .put("mimeType", geminiAudioMime(audio.optString("format")))
                                .put("data", data)))
                        }
                    }
                }
            }
            else -> content?.let { parts.put(JSONObject().put("text", it.toString())) }
        }
        return parts
    }

    private fun geminiAudioMime(format: String): String = when (format.lowercase()) {
        "mp3", "mpeg" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "mp4", "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "ogg" -> "audio/ogg"
        "flac" -> "audio/flac"
        "amr" -> "audio/amr"
        "opus" -> "audio/opus"
        else -> "audio/mpeg"
    }

    private fun geminiTools(tools: JSONArray): JSONArray {
        val declarations = JSONArray()
        for (i in 0 until tools.length()) {
            val fn = tools.optJSONObject(i)?.optJSONObject("function") ?: continue
            declarations.put(JSONObject().apply {
                put("name", fn.optString("name"))
                put("description", fn.optString("description"))
                put("parameters", geminiSchema(fn.optJSONObject("parameters") ?: JSONObject()))
            })
        }
        return JSONArray().put(JSONObject().put("functionDeclarations", declarations))
    }

    /** Gemini 的参数 schema 需要大写的类型名，且不接受空的 required 数组。 */
    private fun geminiSchema(schema: JSONObject): JSONObject {
        val out = JSONObject()
        val keys = schema.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            when (val v = schema.opt(k)) {
                is JSONObject -> out.put(k, geminiSchema(v))
                is JSONArray -> {
                    if (k == "required" && v.length() == 0) continue
                    out.put(k, JSONArray().apply {
                        for (i in 0 until v.length()) {
                            val e = v.opt(i)
                            if (e is JSONObject) put(geminiSchema(e)) else put(e)
                        }
                    })
                }
                is String -> out.put(k, if (k == "type") v.uppercase() else v)
                null -> {}
                else -> out.put(k, v)
            }
        }
        return out
    }
}

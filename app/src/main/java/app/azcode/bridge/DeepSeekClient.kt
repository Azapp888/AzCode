package app.azcode.bridge

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class ToolCall(val id: String, val name: String, val arguments: String)

data class AssistantReply(val content: String?, val toolCalls: List<ToolCall>)

/**
 * 模型客户端：按 [ProviderProtocol] 选择请求格式，支持 OpenAI 兼容 / Anthropic Messages / Google Gemini。
 * 仅使用 JDK 标准库 HttpURLConnection 与 org.json，不引入额外依赖。
 *
 * 内部消息统一采用 OpenAI 格式（role/content/tool_calls/...），在各协议适配层再转换。
 */
object DeepSeekClient {

    fun chat(
        provider: ProviderAccount,
        messages: JSONArray,
        tools: JSONArray,
        reasoningEffort: String? = null,
    ): AssistantReply = when (provider.protocol) {
        ProviderProtocol.OPENAI -> chatOpenAi(provider, messages, tools, reasoningEffort)
        ProviderProtocol.ANTHROPIC -> chatAnthropic(provider, messages, tools)
        ProviderProtocol.GEMINI -> chatGemini(provider, messages, tools)
    }

    // ==================== OpenAI 兼容 ====================

    private fun chatOpenAi(
        provider: ProviderAccount,
        messages: JSONArray,
        tools: JSONArray,
        reasoningEffort: String?,
    ): AssistantReply {
        val url = provider.baseUrl.trimEnd('/') + "/chat/completions"
        val headers = mapOf("Authorization" to "Bearer ${provider.apiKey}")

        fun body(withEffort: Boolean): JSONObject = JSONObject().apply {
            put("model", provider.model)
            put("temperature", 0.2)
            put("messages", messages)
            if (tools.length() > 0) {
                put("tools", tools)
                put("tool_choice", "auto")
            }
            if (withEffort && reasoningEffort != null) put("reasoning_effort", reasoningEffort)
        }

        val (code, text) = httpPost(url, headers, body(true).toString())
        // 部分网关不识别 reasoning_effort，遇到此类错误时去掉该参数重试一次。
        if (code !in 200..299 && reasoningEffort != null && text.contains("reasoning_effort")) {
            val (retryCode, retryText) = httpPost(url, headers, body(false).toString())
            if (retryCode !in 200..299) throw apiError(provider, retryCode, retryText)
            return parseOpenAi(retryText)
        }
        if (code !in 200..299) throw apiError(provider, code, text)
        return parseOpenAi(text)
    }

    private fun parseOpenAi(text: String): AssistantReply {
        val message = JSONObject(text)
            .getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        val content = if (message.isNull("content")) null else message.optString("content").ifEmpty { null }

        val calls = mutableListOf<ToolCall>()
        message.optJSONArray("tool_calls")?.let { arr ->
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                val fn = c.getJSONObject("function")
                calls.add(
                    ToolCall(
                        id = c.optString("id"),
                        name = fn.optString("name"),
                        arguments = fn.optString("arguments", "{}"),
                    )
                )
            }
        }
        return AssistantReply(content, calls)
    }

    // ==================== Anthropic Messages ====================

    private fun chatAnthropic(
        provider: ProviderAccount,
        messages: JSONArray,
        tools: JSONArray,
    ): AssistantReply {
        val url = provider.baseUrl.trimEnd('/') + "/messages"
        val (system, converted) = convertToAnthropic(messages)
        val body = JSONObject().apply {
            put("model", provider.model)
            put("max_tokens", 8192)
            put("temperature", 0.2)
            if (system.isNotBlank()) put("system", system)
            put("messages", converted)
            if (tools.length() > 0) put("tools", anthropicTools(tools))
        }
        val headers = mapOf(
            "x-api-key" to provider.apiKey,
            "anthropic-version" to "2023-06-01",
        )
        val (code, text) = httpPost(url, headers, body.toString())
        if (code !in 200..299) throw apiError(provider, code, text)
        return parseAnthropic(text)
    }

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

    private fun parseAnthropic(text: String): AssistantReply {
        val content = JSONObject(text).optJSONArray("content") ?: JSONArray()
        val sb = StringBuilder()
        val calls = mutableListOf<ToolCall>()
        for (i in 0 until content.length()) {
            val b = content.optJSONObject(i) ?: continue
            when (b.optString("type")) {
                "text" -> sb.append(b.optString("text"))
                "tool_use" -> calls.add(
                    ToolCall(
                        id = b.optString("id"),
                        name = b.optString("name"),
                        arguments = (b.optJSONObject("input") ?: JSONObject()).toString(),
                    )
                )
            }
        }
        return AssistantReply(sb.toString().ifBlank { null }, calls)
    }

    // ==================== Google Gemini ====================

    private fun chatGemini(
        provider: ProviderAccount,
        messages: JSONArray,
        tools: JSONArray,
    ): AssistantReply {
        val url = provider.baseUrl.trimEnd('/') +
            "/models/${provider.model}:generateContent?key=${provider.apiKey}"
        val (system, contents) = convertToGemini(messages)
        val body = JSONObject().apply {
            if (system.isNotBlank()) {
                put("systemInstruction", JSONObject().put(
                    "parts", JSONArray().put(JSONObject().put("text", system))
                ))
            }
            put("contents", contents)
            if (tools.length() > 0) put("tools", geminiTools(tools))
            put("generationConfig", JSONObject().put("temperature", 0.2))
        }
        val (code, text) = httpPost(url, emptyMap(), body.toString())
        if (code !in 200..299) throw apiError(provider, code, text)
        return parseGemini(text)
    }

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
                }
            }
            else -> content?.let { parts.put(JSONObject().put("text", it.toString())) }
        }
        return parts
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

    private fun parseGemini(text: String): AssistantReply {
        val parts = JSONObject(text)
            .optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        val sb = StringBuilder()
        val calls = mutableListOf<ToolCall>()
        for (i in 0 until parts.length()) {
            val p = parts.optJSONObject(i) ?: continue
            if (p.has("text")) sb.append(p.optString("text"))
            p.optJSONObject("functionCall")?.let { fc ->
                calls.add(
                    ToolCall(
                        id = "gemini_${UUID.randomUUID()}",
                        name = fc.optString("name"),
                        arguments = (fc.optJSONObject("args") ?: JSONObject()).toString(),
                    )
                )
            }
        }
        return AssistantReply(sb.toString().ifBlank { null }, calls)
    }

    // ==================== 文生图（OpenAI 兼容） ====================

    /**
     * 文生图。使用账号自身的协议、地址与 Key，返回图片地址列表
     * （远程 URL 或 `data:image/...;base64,...`），交由 ImageLoader 展示。
     */
    fun generateImage(
        provider: ProviderAccount,
        prompt: String,
        size: String = "1024x1024",
        count: Int = 1,
    ): List<String> {
        if (provider.protocol != ProviderProtocol.OPENAI) {
            throw RuntimeException("${provider.name} 协议的文生图暂未支持，请改用 OpenAI 兼容协议")
        }
        val url = provider.baseUrl.trimEnd('/') + "/images/generations"
        val headers = mapOf("Authorization" to "Bearer ${provider.apiKey}")

        fun body(format: String): JSONObject = JSONObject().apply {
            put("model", provider.imageModel)
            put("prompt", prompt)
            put("n", count.coerceIn(1, 4))
            put("size", size)
            put("response_format", format)
        }

        val (code, text) = httpPost(url, headers, body("url").toString())
        if (code in 200..299) return parseImages(text)

        // 部分网关不支持 response_format=url，回退到 b64_json。
        val (retryCode, retryText) = httpPost(url, headers, body("b64_json").toString())
        if (retryCode in 200..299) return parseImages(retryText)
        throw apiError(provider, code, text)
    }

    private fun parseImages(text: String): List<String> {
        val data = JSONObject(text).optJSONArray("data")
            ?: throw RuntimeException("图像生成返回格式异常：${text.take(200)}")
        val urls = mutableListOf<String>()
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val remote = item.optString("url")
            if (remote.isNotBlank()) {
                urls.add(remote)
                continue
            }
            val b64 = item.optString("b64_json")
            if (b64.isNotBlank()) urls.add("data:image/png;base64,$b64")
        }
        if (urls.isEmpty()) throw RuntimeException("图像生成未返回图片")
        return urls
    }

    // ==================== 模型列表 ====================

    /** 从提供商的 /models 接口拉取可用模型名列表。 */
    fun listModels(protocol: ProviderProtocol, baseUrl: String, apiKey: String): List<String> {
        val base = baseUrl.trimEnd('/')
        return when (protocol) {
            ProviderProtocol.OPENAI -> {
                val (code, text) = httpGet("$base/models", mapOf("Authorization" to "Bearer $apiKey"))
                if (code !in 200..299) throw RuntimeException("拉取模型列表 HTTP $code: ${text.take(300)}")
                parseOpenAiModels(text)
            }
            ProviderProtocol.ANTHROPIC -> {
                val (code, text) = httpGet(
                    "$base/models",
                    mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01"),
                )
                if (code !in 200..299) throw RuntimeException("拉取模型列表 HTTP $code: ${text.take(300)}")
                parseDataIdModels(text)
            }
            ProviderProtocol.GEMINI -> {
                val (code, text) = httpGet("$base/models?key=$apiKey", emptyMap())
                if (code !in 200..299) throw RuntimeException("拉取模型列表 HTTP $code: ${text.take(300)}")
                val arr = JSONObject(text).optJSONArray("models") ?: JSONArray()
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.optString("name")?.removePrefix("models/")?.takeIf { it.isNotBlank() }
                }
            }
        }
    }

    private fun parseOpenAiModels(text: String): List<String> =
        parseDataIdModels(text)

    private fun parseDataIdModels(text: String): List<String> {
        val arr = JSONObject(text).optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }
        }
    }

    private fun httpGet(url: String, headers: Map<String, String>): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        return code to text
    }

    // ==================== HTTP ====================

    private fun httpPost(url: String, headers: Map<String, String>, body: String): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        return code to text
    }

    private fun apiError(provider: ProviderAccount, code: Int, text: String): RuntimeException =
        RuntimeException("${provider.name} HTTP $code: ${text.take(400)}")

    /**
     * 构造 user 消息内容：无附件时为纯字符串；有附件时返回 OpenAI 兼容的内容块数组
     * （文本块 + 图片块）。
     */
    fun buildUserContent(
        task: String,
        attachments: List<AttachmentReader.Prepared>,
    ): Any {
        if (attachments.isEmpty()) return task

        val texts = attachments.filterIsInstance<AttachmentReader.Prepared.Text>()
        val images = attachments.filterIsInstance<AttachmentReader.Prepared.Image>()

        val sb = StringBuilder(task)
        texts.forEach { t ->
            sb.append("\n\n【附件：").append(t.name).append("】\n")
            sb.append(t.text)
        }

        val blocks = JSONArray().apply {
            put(JSONObject().put("type", "text").put("text", sb.toString()))
            images.forEach { img ->
                put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject()
                        .put("url", "data:${img.mime};base64,${img.base64}"))
                })
            }
        }
        return blocks
    }
}

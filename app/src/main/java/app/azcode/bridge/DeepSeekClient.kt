package app.azcode.bridge

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ToolCall(val id: String, val name: String, val arguments: String)

data class AssistantReply(val content: String?, val toolCalls: List<ToolCall>)

/**
 * DeepSeek（OpenAI 兼容）chat/completions 客户端，支持 function calling 与图片输入。
 * 仅使用 JDK 标准库 HttpURLConnection 与 org.json，不引入额外依赖。
 */
object DeepSeekClient {

    fun chat(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: JSONArray,
        tools: JSONArray,
        reasoningEffort: String? = null,
    ): AssistantReply {
        val url = baseUrl.trimEnd('/') + "/chat/completions"

        fun buildBody(withEffort: Boolean): JSONObject = JSONObject().apply {
            put("model", model)
            put("temperature", 0.2)
            put("messages", messages)
            if (tools.length() > 0) {
                put("tools", tools)
                put("tool_choice", "auto")
            }
            if (withEffort && reasoningEffort != null) put("reasoning_effort", reasoningEffort)
        }

        val first = buildBody(true)
        val (code, text) = post(url, apiKey, first)

        // 部分网关不识别 reasoning_effort，遇到此类错误时去掉该参数重试一次。
        if (code !in 200..299 && reasoningEffort != null && text.contains("reasoning_effort")) {
            val (retryCode, retryText) = post(url, apiKey, buildBody(false))
            if (retryCode !in 200..299) throw RuntimeException("DeepSeek HTTP $retryCode: ${retryText.take(400)}")
            return parse(retryText)
        }
        if (code !in 200..299) throw RuntimeException("DeepSeek HTTP $code: ${text.take(400)}")
        return parse(text)
    }

    private fun post(url: String, apiKey: String, body: JSONObject): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        return code to text
    }

    private fun parse(text: String): AssistantReply {
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

    /**
     * 文生图（OpenAI 兼容 images/generations）。返回图片地址列表，
     * 远程 URL 或 `data:image/...;base64,...` 形式均可，交由 ImageLoader 展示。
     */
    fun generateImage(
        baseUrl: String,
        apiKey: String,
        imageModel: String,
        prompt: String,
        size: String = "1024x1024",
        count: Int = 1,
    ): List<String> {
        val url = baseUrl.trimEnd('/') + "/images/generations"

        fun body(format: String): JSONObject = JSONObject().apply {
            put("model", imageModel)
            put("prompt", prompt)
            put("n", count.coerceIn(1, 4))
            put("size", size)
            put("response_format", format)
        }

        val (code, text) = post(url, apiKey, body("url"))
        if (code in 200..299) return parseImages(text)

        // 部分网关不支持 response_format=url，回退到 b64_json。
        val (retryCode, retryText) = post(url, apiKey, body("b64_json"))
        if (retryCode in 200..299) return parseImages(retryText)
        throw RuntimeException("图像生成 HTTP $code: ${text.take(400)}")
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
}

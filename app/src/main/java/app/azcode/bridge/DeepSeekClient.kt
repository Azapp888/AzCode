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
    ): AssistantReply {
        val body = JSONObject().apply {
            put("model", model)
            put("temperature", 0.2)
            put("messages", messages)
            if (tools.length() > 0) {
                put("tools", tools)
                put("tool_choice", "auto")
            }
        }

        val conn = (URL(baseUrl.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection).apply {
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
        if (code !in 200..299) throw RuntimeException("DeepSeek HTTP $code: ${text.take(400)}")

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
}

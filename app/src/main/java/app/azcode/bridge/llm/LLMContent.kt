package app.azcode.bridge.llm

import app.azcode.bridge.AttachmentReader
import org.json.JSONArray
import org.json.JSONObject

/**
 * 构造内部统一的 user 消息内容：
 * 无附件时为纯字符串；有附件时为 OpenAI 语义的内容块数组（文本块 + 图片块 + 音频块）。
 * 与具体厂商无关，各适配器自行决定如何翻译多模态内容。
 */
object LLMContent {

    fun buildUserContent(
        task: String,
        attachments: List<AttachmentReader.Prepared>,
    ): Any {
        if (attachments.isEmpty()) return task

        val texts = attachments.filterIsInstance<AttachmentReader.Prepared.Text>()
        val images = attachments.filterIsInstance<AttachmentReader.Prepared.Image>()
        val audios = attachments.filterIsInstance<AttachmentReader.Prepared.Audio>()

        val sb = StringBuilder(task)
        texts.forEach { t ->
            sb.append("\n\n【附件：").append(t.name).append("】\n")
            sb.append(t.text)
        }
        audios.forEach { a ->
            sb.append("\n\n【音频附件：").append(a.name).append("】")
        }

        return JSONArray().apply {
            put(JSONObject().put("type", "text").put("text", sb.toString()))
            images.forEach { img ->
                put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject()
                        .put("url", "data:${img.mime};base64,${img.base64}"))
                })
            }
            audios.forEach { audio ->
                put(JSONObject().apply {
                    put("type", "input_audio")
                    put("input_audio", JSONObject()
                        .put("data", audio.base64)
                        .put("format", audio.format))
                })
            }
        }
    }
}

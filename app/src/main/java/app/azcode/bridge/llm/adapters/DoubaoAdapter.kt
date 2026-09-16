package app.azcode.bridge.llm.adapters

import app.azcode.bridge.ProviderAccount
import app.azcode.bridge.llm.core.LLMThinking
import org.json.JSONObject

/**
 * 火山方舟（豆包）适配器。
 *
 * 与 OpenAI 兼容体的差异：
 * - 思考字段为 `reasoning`，取值为 `enabled` / `disabled`；
 * - 文生图（Seedream）不支持 `n`，多图用 `sequential_image_generation` +
 *   `sequential_image_generation_options.max_images` 表达，水印由全局开关控制；
 * - 模型字段若网关要求 `model_name`，覆写 [modelField] 为 `"model_name"` 即可。
 */
class DoubaoAdapter : OpenAiCompatAdapter() {

    override val id: String = "doubao"
    override val label: String = "火山方舟（豆包）"

    override val thinkingParamKeys: Set<String> = setOf("reasoning")

    override fun applyThinking(body: JSONObject, thinking: LLMThinking) {
        body.put("reasoning", if (thinking.enabled) "enabled" else "disabled")
    }

    /** 组图能力仅部分 Seedream 版本支持；3.0 t2i / seededit 只能单图。 */
    private fun supportsSequential(account: ProviderAccount): Boolean {
        val m = account.imageModel.lowercase()
        return !m.contains("seedream-3") && !m.contains("seededit")
    }

    override fun imageBatchSize(account: ProviderAccount): Int =
        if (supportsSequential(account)) 4 else 1

    override fun imageRequestBody(
        account: ProviderAccount,
        prompt: String,
        size: String,
        count: Int,
        format: String?,
        watermark: Boolean,
    ): JSONObject = JSONObject().apply {
        put("model", account.imageModel)
        put("prompt", prompt)
        put("size", size)
        // 平台默认会给图片加“AI生成”水印，这里按用户设置显式指定。
        put("watermark", watermark)
        if (format != null) put("response_format", format)
        if (supportsSequential(account)) {
            if (count > 1) {
                put("sequential_image_generation", "auto")
                put(
                    "sequential_image_generation_options",
                    JSONObject().put("max_images", count.coerceIn(1, 15)),
                )
            } else {
                put("sequential_image_generation", "disabled")
            }
        }
    }
}

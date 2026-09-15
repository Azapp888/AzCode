package app.azcode.bridge.llm.adapters

import app.azcode.bridge.ProviderAccount
import org.json.JSONObject

/**
 * 硅基流动 SiliconFlow 适配器。
 *
 * 图片生成接口与 OpenAI 兼容体差异较大：
 * - 尺寸字段为 `image_size`（而非 `size`）；
 * - 数量字段为 `batch_size`（而非 `n`），且平台已宣布移除该字段，这里改为逐张请求；
 * - 不接受 `response_format`，固定返回 `data[].url`。
 */
class SiliconFlowAdapter : OpenAiCompatAdapter() {

    override val id: String = "siliconflow"
    override val label: String = "硅基流动 SiliconFlow"

    override val thinkingParamKeys: Set<String> = setOf("reasoning_effort")

    /** 不携带 response_format，逐张生成，避免依赖已被平台移除的 batch_size。 */
    override fun imageFormatParams(account: ProviderAccount): List<String?> = listOf<String?>(null)

    override fun imageBatchSize(account: ProviderAccount): Int = 1

    override fun imageRequestBody(
        account: ProviderAccount,
        prompt: String,
        size: String,
        count: Int,
        format: String?,
    ): JSONObject = JSONObject().apply {
        put("model", account.imageModel)
        put("prompt", prompt)
        put("image_size", size)
        put("num_inference_steps", 20)
    }
}

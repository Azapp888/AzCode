package app.azcode.bridge.llm.adapters

import app.azcode.bridge.ProviderAccount

/**
 * 标准 OpenAI 适配器，同时作为所有未识别厂商的通用 OpenAI 兼容实现。
 *
 * - 思考参数使用官方 `reasoning_effort`；
 * - `gpt-image-*` 不接受 `response_format`（固定返回 b64_json）；
 * - `dall-e-3` 单次只能生成一张，由基类自动拆成多次请求。
 */
class OpenAiAdapter : OpenAiCompatAdapter() {

    override val id: String = "openai"
    override val label: String = "OpenAI 兼容"

    override val thinkingParamKeys: Set<String> = setOf("reasoning_effort")

    override fun imageFormatParams(account: ProviderAccount): List<String?> =
        if (account.imageModel.startsWith("gpt-image")) listOf<String?>(null) else listOf("url", "b64_json")

    override fun imageBatchSize(account: ProviderAccount): Int =
        if (account.imageModel.startsWith("dall-e-3")) 1 else 4
}

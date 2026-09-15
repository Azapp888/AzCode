package app.azcode.bridge.llm.adapters

/**
 * 标准 OpenAI 适配器，同时作为所有未识别厂商的通用 OpenAI 兼容实现。
 * 思考参数使用官方 `reasoning_effort`。
 */
class OpenAiAdapter : OpenAiCompatAdapter() {

    override val id: String = "openai"
    override val label: String = "OpenAI 兼容"

    override val thinkingParamKeys: Set<String> = setOf("reasoning_effort")
}

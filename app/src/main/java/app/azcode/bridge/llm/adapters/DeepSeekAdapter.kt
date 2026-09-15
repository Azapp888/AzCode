package app.azcode.bridge.llm.adapters

import app.azcode.bridge.llm.core.LLMThinking
import org.json.JSONObject

/**
 * DeepSeek 适配器。与 OpenAI 兼容体的唯一差异是思考参数：
 * 内部 thinking 翻译为官方的 `think_effort`，并在开启时附带 `reasoning_effort`。
 */
class DeepSeekAdapter : OpenAiCompatAdapter() {

    override val id: String = "deepseek"
    override val label: String = "DeepSeek"

    override val thinkingParamKeys: Set<String> = setOf("think_effort", "reasoning_effort")

    override fun applyThinking(body: JSONObject, thinking: LLMThinking) {
        body.put("think_effort", JSONObject().put("enable", thinking.enabled))
        if (thinking.enabled && thinking.effort != null) {
            body.put("reasoning_effort", thinking.effort)
        }
    }
}

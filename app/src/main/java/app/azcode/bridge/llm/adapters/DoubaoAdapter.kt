package app.azcode.bridge.llm.adapters

import app.azcode.bridge.llm.core.LLMThinking
import org.json.JSONObject

/**
 * 火山方舟（豆包）适配器。
 *
 * 与 OpenAI 兼容体的差异：
 * - 思考字段为 `reasoning`，取值为 `enabled` / `disabled`；
 * - 模型字段若网关要求 `model_name`，覆写 [modelField] 为 `"model_name"` 即可。
 */
class DoubaoAdapter : OpenAiCompatAdapter() {

    override val id: String = "doubao"
    override val label: String = "火山方舟（豆包）"

    override val thinkingParamKeys: Set<String> = setOf("reasoning")

    override fun applyThinking(body: JSONObject, thinking: LLMThinking) {
        body.put("reasoning", if (thinking.enabled) "enabled" else "disabled")
    }
}

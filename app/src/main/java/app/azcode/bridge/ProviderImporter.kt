package app.azcode.bridge

import android.content.Context
import app.azcode.bridge.llm.LLMService
import app.azcode.bridge.llm.core.LLMCodec
import app.azcode.bridge.llm.core.LLMRequest
import app.azcode.bridge.llm.core.LLMThinking
import org.json.JSONArray
import org.json.JSONObject

/**
 * 通过 Markdown/文本批量导入模型提供商。
 * 依赖一个已配置可用的模型：用它把文档解析成结构化的提供商列表。
 */
object ProviderImporter {

    private const val SYSTEM_PROMPT = """你是模型配置解析器。用户会给你一段描述多个大模型平台的 Markdown 或文本。
请提取其中的平台信息，输出一个 JSON 数组，不要输出任何解释或代码块标记。
数组每个元素字段如下：
- name: 平台名称（字符串）
- protocol: 协议，取值 openai / anthropic / gemini，默认 openai
- baseUrl: API 基础地址（字符串）
- apiKey: API 密钥（字符串，没有则留空）
- model: 默认语言模型名（字符串）
- models: 该平台可用的语言模型名数组（字符串数组）
- imageModel: 文生图模型名（字符串，没有则留空）
- imageModels: 该平台可用的文生图模型名数组（字符串数组，没有则省略）
- supportsReasoningEffort: 是否支持 reasoning_effort（布尔，默认 false）
若文档未提供某字段则省略该字段。只输出 JSON 数组本身。"""

    fun parse(ctx: Context, content: String): List<ProviderAccount> {
        val provider = AgentConfig.activeProvider(ctx)
            ?: throw RuntimeException("需要先有一个已配置好的模型才能解析文档")
        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
            put(JSONObject().put("role", "user").put("content", content.take(20000)))
        }
        val reply = LLMService.chat(
            provider,
            LLMRequest(
                model = provider.model,
                messages = LLMCodec.openAiToMessages(messages),
                thinking = LLMThinking.OFF,
                omitThinkingParam = true,
            ),
        )
        val text = reply.content?.trim()
            ?: throw RuntimeException("模型未返回解析结果")
        val arr = JSONArray(extractJsonArray(text))

        val accounts = mutableListOf<ProviderAccount>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val baseUrl = o.optString("baseUrl").trim()
            val models = stringList(o.optJSONArray("models")).ifEmpty {
                listOfNotNull(o.optString("model").trim().takeIf { it.isNotBlank() })
            }
            if (baseUrl.isBlank() || models.isEmpty()) continue
            val protocol = ProviderProtocol.from(o.optString("protocol"))
            val imageModels = stringList(o.optJSONArray("imageModels")).ifEmpty {
                listOfNotNull(o.optString("imageModel").trim().takeIf { it.isNotBlank() })
            }
            val apiKey = o.optString("apiKey").trim()
            accounts.add(
                ProviderAccount(
                    id = AgentConfig.newAccountId(),
                    name = o.optString("name").trim().ifBlank { protocol.label },
                    protocol = protocol,
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    enabled = true,
                    models = models,
                    model = models.first(),
                    supportsReasoningEffort = o.optBoolean(
                        "supportsReasoningEffort",
                        protocol == ProviderProtocol.OPENAI && baseUrl.contains("deepseek"),
                    ),
                    imageEnabled = protocol.supportsImage && imageModels.isNotEmpty(),
                    imageModels = imageModels,
                    imageModel = imageModels.firstOrNull().orEmpty(),
                )
            )
        }
        return accounts
    }

    /** 从可能包含说明文字的模型回复中提取最外层的 JSON 数组。 */
    private fun extractJsonArray(text: String): String {
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start in 0 until end) return text.substring(start, end + 1)
        throw RuntimeException("未能从模型输出中解析出 JSON")
    }

    private fun stringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = LinkedHashSet<String>()
        for (i in 0 until arr.length()) {
            val s = arr.optString(i).trim()
            if (s.isNotEmpty()) out.add(s)
        }
        return out.toList()
    }
}

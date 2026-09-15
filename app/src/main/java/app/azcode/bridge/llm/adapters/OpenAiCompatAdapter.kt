package app.azcode.bridge.llm.adapters

import app.azcode.bridge.ProviderAccount
import app.azcode.bridge.llm.core.BaseAdapter
import app.azcode.bridge.llm.core.LLMCodec
import app.azcode.bridge.llm.core.LLMErrorCode
import app.azcode.bridge.llm.core.LLMException
import app.azcode.bridge.llm.core.LLMRequest
import app.azcode.bridge.llm.core.LLMResponse
import app.azcode.bridge.llm.core.LLMThinking
import app.azcode.bridge.llm.core.LLMTransport
import org.json.JSONObject

/**
 * OpenAI 兼容协议适配器基类。DeepSeek / 火山方舟 / 硅基流动 / OpenAI 等绝大多数平台
 * 都沿用这套请求与响应格式，差异集中在「思考参数」上，由子类覆写 [applyThinking]。
 */
abstract class OpenAiCompatAdapter : BaseAdapter() {

    override fun endpoint(account: ProviderAccount, request: LLMRequest): String =
        account.baseUrl.trimEnd('/') + "/chat/completions"

    override fun headers(account: ProviderAccount): Map<String, String> =
        mapOf("Authorization" to "Bearer ${account.apiKey}")

    /**
     * 模型字段名。个别网关使用非标准字段名（如 `model_name`），在子类覆写即可，
     * 业务代码无感知。
     */
    protected open val modelField: String = "model"

    override fun translateRequest(request: LLMRequest): JSONObject {
        val body = JSONObject().apply {
            put(modelField, request.model)
            request.temperature?.let { put("temperature", it) }
            put("messages", LLMCodec.messagesJson(request.messages))
            if (request.tools.isNotEmpty()) {
                put("tools", LLMCodec.toolsJson(request.tools))
                put("tool_choice", "auto")
            }
            if (request.stream) put("stream", true)
        }
        if (!request.omitThinkingParam) applyThinking(body, request.thinking)
        return body
    }

    /** 各厂商在 OpenAI 兼容体上对「思考」字段的差异。默认使用 reasoning_effort。 */
    protected open fun applyThinking(body: JSONObject, thinking: LLMThinking) {
        if (thinking.enabled && thinking.effort != null) body.put("reasoning_effort", thinking.effort)
    }

    override fun translateResponse(raw: JSONObject): LLMResponse = LLMCodec.parseOpenAiResponse(raw)

    override fun generateImage(
        account: ProviderAccount,
        prompt: String,
        size: String,
        count: Int,
    ): List<String> {
        val url = account.baseUrl.trimEnd('/') + "/images/generations"
        val headers = mapOf("Authorization" to "Bearer ${account.apiKey}")

        fun body(format: String): JSONObject = JSONObject().apply {
            put("model", account.imageModel)
            put("prompt", prompt)
            put("n", count.coerceIn(1, 4))
            put("size", size)
            put("response_format", format)
        }

        val (code, text) = LLMTransport.post(url, headers, body("url").toString())
        if (code in 200..299) return parseImages(text)

        // 部分网关不支持 response_format=url，回退到 b64_json。
        val (retryCode, retryText) = LLMTransport.post(url, headers, body("b64_json").toString())
        if (retryCode in 200..299) return parseImages(retryText)
        throw translateError(account, code, text)
    }

    private fun parseImages(text: String): List<String> {
        val data = JSONObject(text).optJSONArray("data")
            ?: throw LLMException(LLMErrorCode.INVALID_REQUEST, "图像生成返回格式异常：${text.take(200)}")
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
        if (urls.isEmpty()) throw LLMException(LLMErrorCode.INVALID_REQUEST, "图像生成未返回图片")
        return urls
    }

    override fun listModels(baseUrl: String, apiKey: String): List<String> {
        val base = baseUrl.trimEnd('/')
        val (code, text) = LLMTransport.get(
            "$base/models",
            mapOf("Authorization" to "Bearer $apiKey"),
        )
        if (code !in 200..299) {
            throw LLMException(
                LLMErrorCode.fromHttp(code),
                "拉取模型列表 HTTP $code：${text.take(300)}",
                httpStatus = code,
            )
        }
        val arr = JSONObject(text).optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }
        }
    }
}

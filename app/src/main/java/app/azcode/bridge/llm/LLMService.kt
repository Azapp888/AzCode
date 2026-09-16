package app.azcode.bridge.llm

import app.azcode.bridge.ProviderAccount
import app.azcode.bridge.ProviderProtocol
import app.azcode.bridge.llm.core.LLMRequest
import app.azcode.bridge.llm.core.LLMResponse
import app.azcode.bridge.llm.core.LLMTransport
import org.json.JSONObject

/**
 * 模型服务门面。业务代码统一通过它调用模型，不感知厂商差异：
 * 内部统一 [LLMRequest] / [LLMResponse]，由 [LLMRegistry] 路由到具体厂商适配器完成翻译。
 */
object LLMService {

    /**
     * 对话补全。网关若不识别思考参数（400/422 且报错包含该字段名），
     * 自动去掉思考参数重试一次，保证跨网关兼容。
     */
    fun chat(account: ProviderAccount, request: LLMRequest): LLMResponse {
        val adapter = LLMRegistry.adapterFor(account, request.model)
        val url = adapter.endpoint(account, request)
        val headers = adapter.headers(account)

        val (code, text) = LLMTransport.post(url, headers, adapter.translateRequest(request).toString())
        if (code in 200..299) return adapter.translateResponse(JSONObject(text))

        val canRetryWithoutThinking = !request.omitThinkingParam &&
            (code == 400 || code == 422) &&
            adapter.thinkingParamKeys.isNotEmpty() &&
            adapter.thinkingParamKeys.any { text.contains(it) }
        if (canRetryWithoutThinking) {
            val retry = request.copy(omitThinkingParam = true)
            val (retryCode, retryText) =
                LLMTransport.post(url, headers, adapter.translateRequest(retry).toString())
            if (retryCode in 200..299) return adapter.translateResponse(JSONObject(retryText))
        }
        throw adapter.translateError(account, code, text)
    }

    /** 文生图，返回图片地址列表（远程 URL 或 data:image base64）。`watermark` 仅对支持该参数的厂商生效。 */
    fun generateImage(
        account: ProviderAccount,
        prompt: String,
        size: String = "1024x1024",
        count: Int = 1,
        watermark: Boolean = false,
    ): List<String> = LLMRegistry.adapterFor(account, account.imageModel)
        .generateImage(account, prompt, size, count, watermark)

    /** 拉取可用模型名列表。 */
    fun listModels(protocol: ProviderProtocol, baseUrl: String, apiKey: String): List<String> =
        LLMRegistry.adapterForProtocol(protocol).listModels(baseUrl, apiKey)

    /** 当前账号实际命中的厂商（调试/展示用）。 */
    fun vendorOf(account: ProviderAccount, model: String): LLMVendor =
        LLMRegistry.detectVendor(account, model)
}

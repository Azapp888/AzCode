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

/** 生图请求的读取超时：单张图常需数十秒，留足余量。 */
private const val IMAGE_READ_TIMEOUT_MS = 180_000

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

    /**
     * 文生图。各厂商在 OpenAI 兼容体上的差异由 [imageEndpoint] / [imageFormatParams] /
     * [imageBatchSize] / [imageRequestBody] 覆写描述；[generateImage] 负责按批次补足张数与
     * 返回格式回退，业务代码无需感知。
     */
    override fun generateImage(
        account: ProviderAccount,
        prompt: String,
        size: String,
        count: Int,
        watermark: Boolean,
    ): List<String> {
        val total = count.coerceIn(1, 4)
        val perRequest = imageBatchSize(account).coerceIn(1, total)
        val urls = mutableListOf<String>()
        var lastError: LLMException? = null
        while (urls.size < total) {
            val want = minOf(perRequest, total - urls.size)
            val batch = try {
                requestImages(account, prompt, size, want, watermark)
            } catch (e: LLMException) {
                lastError = e
                break
            }
            if (batch.isEmpty()) break
            urls.addAll(batch)
            // 平台返回数量少于请求时收敛，避免无限循环。
            if (batch.size < want) break
        }
        if (urls.isNotEmpty()) return urls
        throw lastError ?: LLMException(LLMErrorCode.UNKNOWN, "图像生成未返回图片")
    }

    /** 依次尝试各返回格式，任一成功即返回。 */
    private fun requestImages(
        account: ProviderAccount,
        prompt: String,
        size: String,
        count: Int,
        watermark: Boolean,
    ): List<String> {
        val endpoint = imageEndpoint(account)
        val headers = headers(account)
        var lastError: LLMException? = null
        for (format in imageFormatParams(account)) {
            val body = imageRequestBody(account, prompt, size, count, format, watermark)
            val (code, text) = LLMTransport.post(endpoint, headers, body.toString(), IMAGE_READ_TIMEOUT_MS)
            if (code in 200..299) {
                val parsed = parseImageResponse(text)
                if (parsed.isNotEmpty()) return parsed
                lastError = LLMException(LLMErrorCode.UNKNOWN, "图像生成未返回图片：${text.take(200)}")
                continue
            }
            lastError = translateError(account, code, text)
        }
        throw lastError ?: LLMException(LLMErrorCode.UNKNOWN, "图像生成失败")
    }

    protected open fun imageEndpoint(account: ProviderAccount): String =
        account.baseUrl.trimEnd('/') + "/images/generations"

    /** 需要尝试的 response_format 取值；null 表示不携带该字段。 */
    protected open fun imageFormatParams(account: ProviderAccount): List<String?> = listOf("url", "b64_json")

    /** 单次请求最多生成的图片数；平台单次仅支持一张时返回 1，由 [generateImage] 多次请求补足。 */
    protected open fun imageBatchSize(account: ProviderAccount): Int = 4

    /**
     * 生图请求体。`watermark` 为全局水印开关，仅支持该参数的厂商（如火山方舟 Seedream）
     * 需要覆写使用，其余厂商忽略。
     */
    protected open fun imageRequestBody(
        account: ProviderAccount,
        prompt: String,
        size: String,
        count: Int,
        format: String?,
        watermark: Boolean,
    ): JSONObject = JSONObject().apply {
        put("model", account.imageModel)
        put("prompt", prompt)
        put("n", count.coerceIn(1, 4))
        put("size", size)
        if (format != null) put("response_format", format)
    }

    /** 解析厂商返回体中的图片（远程 URL 或 data:image base64）。 */
    protected open fun parseImageResponse(text: String): List<String> {
        val data = JSONObject(text).optJSONArray("data")
            ?: throw LLMException(LLMErrorCode.UNKNOWN, "图像生成返回格式异常：${text.take(200)}")
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

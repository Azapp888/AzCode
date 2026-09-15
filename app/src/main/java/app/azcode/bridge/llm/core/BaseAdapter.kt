package app.azcode.bridge.llm.core

import app.azcode.bridge.ProviderAccount
import app.azcode.bridge.ProviderProtocol
import org.json.JSONObject

/**
 * 厂商适配器基类。业务代码只与 [LLMRequest] / [LLMResponse] 打交道，
 * 各厂商的字段名、地址、鉴权、错误表示差异全部封装在子类中。
 *
 * 新增一个模型只需：新建一个适配器类 + 在
 * [app.azcode.bridge.llm.LLMRegistry] 注册，无需改动基类与业务代码。
 */
abstract class BaseAdapter {

    /** 适配器唯一标识，与 [app.azcode.bridge.llm.LLMVendor.key] 对应。 */
    abstract val id: String

    /** 展示名。 */
    abstract val label: String

    /** 该适配器所属传输协议族。 */
    open val protocol: ProviderProtocol get() = ProviderProtocol.OPENAI

    /** 对话请求地址。 */
    abstract fun endpoint(account: ProviderAccount, request: LLMRequest): String

    /** 鉴权等请求头。 */
    abstract fun headers(account: ProviderAccount): Map<String, String>

    /** 内部统一请求 -> 厂商请求体。 */
    abstract fun translateRequest(request: LLMRequest): JSONObject

    /** 厂商响应 -> 内部统一响应。 */
    abstract fun translateResponse(raw: JSONObject): LLMResponse

    /**
     * 流式分块（SSE 的一条 data）-> 内部统一响应。
     * 默认返回 null，表示该适配器暂不支持流式；业务可按 [supportsStreaming] 判断。
     */
    open fun translateStreamChunk(data: String): LLMResponse? = null

    open val supportsStreaming: Boolean get() = false

    /**
     * 请求体中「思考」相关字段名。网关报错并命中这些字段时，
     * [app.azcode.bridge.llm.LLMService] 会去掉思考参数重试一次，保证跨网关兼容。
     */
    open val thinkingParamKeys: Set<String> get() = emptySet()

    /** 文生图，返回图片地址列表（远程 URL 或 data:image base64）。不支持时抛出 [LLMException]。 */
    open fun generateImage(
        account: ProviderAccount,
        prompt: String,
        size: String,
        count: Int,
    ): List<String> = throw LLMException(
        LLMErrorCode.INVALID_REQUEST,
        "${account.name} 协议的文生图暂未支持，请改用 OpenAI 兼容协议",
    )

    /** 拉取可用模型名列表。 */
    open fun listModels(baseUrl: String, apiKey: String): List<String> = emptyList()

    /** 厂商错误 -> 内部标准错误。 */
    open fun translateError(account: ProviderAccount, httpStatus: Int, body: String): LLMException =
        LLMException(
            code = LLMErrorCode.fromHttp(httpStatus),
            message = "${account.name} 请求失败（HTTP $httpStatus）：${body.take(400)}",
            httpStatus = httpStatus,
        )
}

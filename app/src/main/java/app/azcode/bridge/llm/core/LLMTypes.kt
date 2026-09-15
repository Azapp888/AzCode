package app.azcode.bridge.llm.core

import org.json.JSONObject

/**
 * 内部统一的思考档位。业务代码只表达「要不要思考、思考多深」，
 * 各厂商字段差异（think_effort / reasoning / reasoning_effort ...）由适配器翻译。
 *
 * key 与 [app.azcode.bridge.AgentConfig.ThinkingDepth] 保持一致，便于直接映射。
 */
enum class LLMThinking(
    val key: String,
    val label: String,
    val enabled: Boolean,
    val effort: String?,
) {
    OFF("off", "关闭", false, null),
    FAST("fast", "快速", true, "low"),
    STANDARD("standard", "标准", true, "medium"),
    DEEP("deep", "深度", true, "high");

    companion object {
        fun from(key: String?): LLMThinking = entries.firstOrNull { it.key == key } ?: STANDARD
    }
}

/** 内部统一的工具调用。 */
data class LLMToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

/** 内部统一的工具声明。 */
data class LLMTool(
    val name: String,
    val description: String,
    val parameters: JSONObject,
)

/**
 * 内部统一的消息。content 为 String（纯文本）或 org.json.JSONArray（多模态内容块）。
 * 内部统一采用 OpenAI 的消息语义（role / content / tool_calls / tool_call_id）。
 */
data class LLMMessage(
    val role: String,
    val content: Any? = null,
    val toolCalls: List<LLMToolCall> = emptyList(),
    val toolCallId: String? = null,
)

/** 内部统一的请求。 */
data class LLMRequest(
    val model: String,
    val messages: List<LLMMessage>,
    val tools: List<LLMTool> = emptyList(),
    val thinking: LLMThinking = LLMThinking.STANDARD,
    val stream: Boolean = false,
    val temperature: Double? = 0.2,
    /**
     * true 表示不携带任何厂商思考参数，用于兼容不识别该参数的老网关。
     * 由 [app.azcode.bridge.llm.LLMService] 在网关报错时自动置位后重试。
     */
    val omitThinkingParam: Boolean = false,
)

/** 内部统一的用量统计。 */
data class LLMUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
)

/**
 * 内部统一的响应。content 为正文，reasoning 为思维链（如有），
 * tool_calls 为工具调用；finish_reason 反映结束原因。
 */
data class LLMResponse(
    val content: String?,
    val reasoning: String? = null,
    val finishReason: String = "stop",
    val usage: LLMUsage = LLMUsage(),
    val toolCalls: List<LLMToolCall> = emptyList(),
)

/** 内部标准错误码：屏蔽各厂商五花八门的错误表示。 */
enum class LLMErrorCode {
    UNAUTHORIZED,
    RATE_LIMIT,
    INVALID_REQUEST,
    SERVER_ERROR,
    NETWORK,
    TIMEOUT,
    UNKNOWN;

    companion object {
        fun fromHttp(status: Int): LLMErrorCode = when (status) {
            401, 403 -> UNAUTHORIZED
            408 -> TIMEOUT
            429 -> RATE_LIMIT
            in 400..499 -> INVALID_REQUEST
            in 500..599 -> SERVER_ERROR
            else -> UNKNOWN
        }
    }
}

/** 内部统一异常：业务代码只需捕获它。 */
class LLMException(
    val code: LLMErrorCode,
    message: String,
    val httpStatus: Int = 0,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

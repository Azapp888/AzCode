package app.azcode.bridge

import org.json.JSONObject

/**
 * 模型提供商的 API 协议。决定请求地址、鉴权头与消息体格式。
 * 目前支持三种主流协议；OpenAI 兼容覆盖 DeepSeek、火山方舟、硅基流动等绝大多数平台。
 */
enum class ProviderProtocol(
    val key: String,
    val label: String,
    /** 该协议是否支持 OpenAI 风格的图像生成接口（images/generations）。 */
    val supportsImage: Boolean,
) {
    OPENAI("openai", "OpenAI 兼容", true),
    ANTHROPIC("anthropic", "Anthropic Messages", false),
    GEMINI("gemini", "Google Gemini", false);

    companion object {
        fun from(key: String?): ProviderProtocol = entries.firstOrNull { it.key == key } ?: OPENAI
    }
}

/**
 * 一个用户配置的模型提供商账号：独立的协议、地址、Key、语言模型与可选生图模型。
 * 每个账号可单独启用/停用，聊天时只会用到「当前使用」的账号。
 */
data class ProviderAccount(
    val id: String,
    val name: String,
    val protocol: ProviderProtocol,
    val baseUrl: String,
    val apiKey: String,
    val enabled: Boolean,
    val model: String,
    val supportsReasoningEffort: Boolean,
    val imageEnabled: Boolean,
    val imageModel: String,
) {
    /** 该账号是否具备可用的文生图配置。 */
    val hasImage: Boolean get() = imageEnabled && imageModel.isNotBlank()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("protocol", protocol.key)
        put("baseUrl", baseUrl)
        put("apiKey", apiKey)
        put("enabled", enabled)
        put("model", model)
        put("supportsReasoningEffort", supportsReasoningEffort)
        put("imageEnabled", imageEnabled)
        put("imageModel", imageModel)
    }

    companion object {
        fun fromJson(o: JSONObject): ProviderAccount = ProviderAccount(
            id = o.optString("id"),
            name = o.optString("name"),
            protocol = ProviderProtocol.from(o.optString("protocol")),
            baseUrl = o.optString("baseUrl"),
            apiKey = o.optString("apiKey"),
            enabled = o.optBoolean("enabled", true),
            model = o.optString("model"),
            supportsReasoningEffort = o.optBoolean("supportsReasoningEffort", false),
            imageEnabled = o.optBoolean("imageEnabled", false),
            imageModel = o.optString("imageModel"),
        )
    }
}

/** 平台预设模板，用于「添加提供商」时快速回填。 */
data class ProviderTemplate(
    val id: String,
    val name: String,
    val protocol: ProviderProtocol,
    val baseUrl: String,
    val model: String,
    val imageModel: String,
    val supportsReasoningEffort: Boolean = false,
)

object ModelProviders {

    const val CUSTOM_ID = "custom"

    val TEMPLATES: List<ProviderTemplate> = listOf(
        ProviderTemplate(
            id = "deepseek",
            name = "DeepSeek",
            protocol = ProviderProtocol.OPENAI,
            baseUrl = "https://api.deepseek.com/v1",
            model = "deepseek-chat",
            imageModel = "",
            supportsReasoningEffort = true,
        ),
        ProviderTemplate(
            id = "volcengine",
            name = "火山方舟（豆包）",
            protocol = ProviderProtocol.OPENAI,
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
            model = "doubao-seed-1-6-250615",
            imageModel = "doubao-seedream-3-0-t2i-250415",
        ),
        ProviderTemplate(
            id = "siliconflow",
            name = "硅基流动 SiliconFlow",
            protocol = ProviderProtocol.OPENAI,
            baseUrl = "https://api.siliconflow.cn/v1",
            model = "deepseek-ai/DeepSeek-V3",
            imageModel = "Kwai-Kolors/Kolors",
        ),
        ProviderTemplate(
            id = "openai",
            name = "OpenAI",
            protocol = ProviderProtocol.OPENAI,
            baseUrl = "https://api.openai.com/v1",
            model = "gpt-4o",
            imageModel = "gpt-image-1",
        ),
        ProviderTemplate(
            id = "anthropic",
            name = "Anthropic Claude",
            protocol = ProviderProtocol.ANTHROPIC,
            baseUrl = "https://api.anthropic.com/v1",
            model = "claude-3-5-sonnet-latest",
            imageModel = "",
        ),
        ProviderTemplate(
            id = "gemini",
            name = "Google Gemini",
            protocol = ProviderProtocol.GEMINI,
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            model = "gemini-2.0-flash",
            imageModel = "",
        ),
    )

    fun templateById(id: String?): ProviderTemplate? = TEMPLATES.firstOrNull { it.id == id }
}

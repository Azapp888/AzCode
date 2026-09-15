package app.azcode.bridge

import org.json.JSONArray
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
 * 一个用户配置的模型提供商账号：独立的协议、地址、Key，可配置多个语言模型与多个生图模型，
 * 并通过 [model] / [imageModel] 记录当前选中的那一个。每个账号可单独启用/停用。
 */
data class ProviderAccount(
    val id: String,
    val name: String,
    val protocol: ProviderProtocol,
    val baseUrl: String,
    val apiKey: String,
    val enabled: Boolean,
    /** 该提供商下全部可用的语言模型。 */
    val models: List<String>,
    /** 当前使用的语言模型。 */
    val model: String,
    val supportsReasoningEffort: Boolean,
    val imageEnabled: Boolean,
    /** 该提供商下全部可用的生图模型。 */
    val imageModels: List<String>,
    /** 当前使用的生图模型。 */
    val imageModel: String,
) {
    /** 该账号是否具备可用的文生图配置。 */
    val hasImage: Boolean get() = imageEnabled && imageModels.isNotEmpty()

    /** 用于界面的语言模型列表：始终包含当前模型且去重。 */
    val allModels: List<String>
        get() = LinkedHashSet<String>().apply {
            addAll(models)
            if (model.isNotBlank()) add(model)
        }.toList()

    val allImageModels: List<String>
        get() = LinkedHashSet<String>().apply {
            addAll(imageModels)
            if (imageModel.isNotBlank()) add(imageModel)
        }.toList()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("protocol", protocol.key)
        put("baseUrl", baseUrl)
        put("apiKey", apiKey)
        put("enabled", enabled)
        put("models", JSONArray(models))
        put("model", model)
        put("supportsReasoningEffort", supportsReasoningEffort)
        put("imageEnabled", imageEnabled)
        put("imageModels", JSONArray(imageModels))
        put("imageModel", imageModel)
    }

    companion object {
        fun fromJson(o: JSONObject): ProviderAccount {
            val model = o.optString("model")
            val models = stringList(o.optJSONArray("models")).ifEmpty {
                listOfNotNull(model.takeIf { it.isNotBlank() })
            }
            val imageModel = o.optString("imageModel")
            val imageModels = stringList(o.optJSONArray("imageModels")).ifEmpty {
                listOfNotNull(imageModel.takeIf { it.isNotBlank() })
            }
            return ProviderAccount(
                id = o.optString("id"),
                name = o.optString("name"),
                protocol = ProviderProtocol.from(o.optString("protocol")),
                baseUrl = o.optString("baseUrl"),
                apiKey = o.optString("apiKey"),
                enabled = o.optBoolean("enabled", true),
                models = models,
                model = model.ifBlank { models.firstOrNull().orEmpty() },
                supportsReasoningEffort = o.optBoolean("supportsReasoningEffort", false),
                imageEnabled = o.optBoolean("imageEnabled", false),
                imageModels = imageModels,
                imageModel = imageModel.ifBlank { imageModels.firstOrNull().orEmpty() },
            )
        }

        private fun stringList(arr: JSONArray?): List<String> {
            if (arr == null) return emptyList()
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val s = arr.optString(i).trim()
                if (s.isNotEmpty()) out.add(s)
            }
            return out
        }
    }
}

/** 平台预设模板，用于「添加提供商」时快速回填。 */
data class ProviderTemplate(
    val id: String,
    val name: String,
    val protocol: ProviderProtocol,
    val baseUrl: String,
    val models: List<String>,
    val imageModels: List<String>,
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
            models = listOf("deepseek-chat", "deepseek-reasoner"),
            imageModels = emptyList(),
            supportsReasoningEffort = true,
        ),
        ProviderTemplate(
            id = "volcengine",
            name = "火山方舟（豆包）",
            protocol = ProviderProtocol.OPENAI,
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
            models = listOf("doubao-seed-1-6-250615", "doubao-1-5-pro-32k-250115"),
            imageModels = listOf("doubao-seedream-3-0-t2i-250415"),
            supportsReasoningEffort = true,
        ),
        ProviderTemplate(
            id = "siliconflow",
            name = "硅基流动 SiliconFlow",
            protocol = ProviderProtocol.OPENAI,
            baseUrl = "https://api.siliconflow.cn/v1",
            models = listOf("deepseek-ai/DeepSeek-V3", "Qwen/Qwen2.5-72B-Instruct"),
            imageModels = listOf("Kwai-Kolors/Kolors"),
        ),
        ProviderTemplate(
            id = "openai",
            name = "OpenAI",
            protocol = ProviderProtocol.OPENAI,
            baseUrl = "https://api.openai.com/v1",
            models = listOf("gpt-4o", "gpt-4o-mini"),
            imageModels = listOf("gpt-image-1", "dall-e-3"),
        ),
        ProviderTemplate(
            id = "anthropic",
            name = "Anthropic Claude",
            protocol = ProviderProtocol.ANTHROPIC,
            baseUrl = "https://api.anthropic.com/v1",
            models = listOf("claude-3-5-sonnet-latest"),
            imageModels = emptyList(),
        ),
        ProviderTemplate(
            id = "gemini",
            name = "Google Gemini",
            protocol = ProviderProtocol.GEMINI,
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            models = listOf("gemini-2.0-flash"),
            imageModels = emptyList(),
        ),
    )

    fun templateById(id: String?): ProviderTemplate? = TEMPLATES.firstOrNull { it.id == id }
}

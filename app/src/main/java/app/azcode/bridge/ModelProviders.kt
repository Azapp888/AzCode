package app.azcode.bridge

/**
 * 模型提供商预设：预设 base URL、聊天模型列表、以及可选的文生图模型名。
 * 选择预设后自动回填设置项，同时保留自定义填写能力。
 *
 * 火山方舟（豆包）走 OpenAI 兼容协议，聊天与 images/generations 共用同一 base URL。
 */
data class ProviderPreset(
    val id: String,
    val name: String,
    val baseUrl: String,
    val chatModels: List<String>,
    /** 文生图模型名；为空表示该提供商预设未配文生图，需用户自行填写。 */
    val imageModel: String,
    /** 是否支持 reasoning_effort 参数（目前仅 DeepSeek）。 */
    val supportsReasoningEffort: Boolean = false,
)

object ModelProviders {

    const val CUSTOM_ID = "custom"

    val ALL: List<ProviderPreset> = listOf(
        ProviderPreset(
            id = "deepseek",
            name = "DeepSeek",
            baseUrl = "https://api.deepseek.com/v1",
            chatModels = listOf("deepseek-flash", "deepseek-chat", "deepseek-reasoner"),
            imageModel = "",
            supportsReasoningEffort = true,
        ),
        ProviderPreset(
            id = "volcengine",
            name = "火山方舟（豆包）",
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
            chatModels = listOf("doubao-seed-1-6-250615", "doubao-1-5-pro-32k-250115"),
            imageModel = "doubao-seedream-3-0-t2i-250415",
        ),
        ProviderPreset(
            id = "siliconflow",
            name = "硅基流动 SiliconFlow",
            baseUrl = "https://api.siliconflow.cn/v1",
            chatModels = listOf("deepseek-ai/DeepSeek-V3", "Qwen/Qwen2.5-72B-Instruct"),
            imageModel = "Kwai-Kolors/Kolors",
        ),
        ProviderPreset(
            id = "openai",
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            chatModels = listOf("gpt-4o", "gpt-4o-mini"),
            imageModel = "gpt-image-1",
        ),
        ProviderPreset(
            id = CUSTOM_ID,
            name = "自定义",
            baseUrl = "",
            chatModels = emptyList(),
            imageModel = "",
        ),
    )

    fun byId(id: String?): ProviderPreset = ALL.firstOrNull { it.id == id } ?: ALL.first()
}
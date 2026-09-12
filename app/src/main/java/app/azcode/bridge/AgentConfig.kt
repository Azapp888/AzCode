package app.azcode.bridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 模型的思考深度：映射到请求的 reasoning_effort，并在系统提示词中体现。
 * OFF 不发送 reasoning_effort。
 */
enum class ThinkingDepth(val key: String, val label: String, val effort: String?) {
    OFF("off", "关闭", null),
    FAST("fast", "快速", "low"),
    STANDARD("standard", "标准", "medium"),
    DEEP("deep", "深度", "high");

    companion object {
        fun from(key: String?): ThinkingDepth = entries.firstOrNull { it.key == key } ?: STANDARD
    }
}

/**
 * Agent 配置：多个模型提供商账号（各自的协议/地址/Key/语言模型/可选生图模型）、
 * 当前使用的账号、思考深度、最大步数与自定义系统提示词。
 * 全部存于应用私有 SharedPreferences；Key 由用户自行填写，应用不读取任何环境变量。
 */
object AgentConfig {

    private const val PREFS = "azcode_agent"
    private const val KEY_PROVIDERS = "providers_json"
    private const val KEY_ACTIVE = "active_provider"
    private const val KEY_DEPTH = "thinking_depth"
    private const val KEY_MAX_STEPS = "max_steps"
    private const val KEY_SYSTEM_PROMPT = "system_prompt"

    // 旧版单账号字段，仅用于迁移。
    private const val LEGACY_API = "api_key"
    private const val LEGACY_BASE = "base_url"
    private const val LEGACY_MODEL = "model"
    private const val LEGACY_IMAGE_MODEL = "image_model"

    const val DEFAULT_BASE = "https://api.deepseek.com/v1"
    const val DEFAULT_MODEL = "deepseek-chat"
    const val DEFAULT_MAX_STEPS = 0 // 0 表示无限步数

    /** 内置默认系统提示词；用户留空时使用。 */
    const val DEFAULT_SYSTEM_PROMPT = """你是 AzCode，一个运行在 Android 手机本地的自动化助手，直接操作用户的手机。
按需调用 get_screen 观察当前界面，只在需要查看屏幕内容或定位控件时才读取，不必每一步都读。
坐标使用屏幕物理像素。优先按文本点击（tap 的 text 字段）以提高鲁棒性；无法定位文本时再用坐标。
执行 shell 前确认任务确实需要；NORMAL 模式下 shell 会失败，此时改用无障碍能力。
面向用户的文字要简洁、口语化，直接说明你正在做什么或最终结果，不要输出 JSON、代码块或工具参数。
任务完成或无法继续时，调用 finish，并在 summary 里用一两句话向用户总结结果。"""

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ==================== 提供商账号 ====================

    @Synchronized
    fun providers(ctx: Context): List<ProviderAccount> {
        val raw = prefs(ctx).getString(KEY_PROVIDERS, "") ?: ""
        val list = parseProviders(raw)
        if (list.isNotEmpty()) return list
        val migrated = migrateLegacy(ctx)
        setProviders(ctx, migrated)
        return migrated
    }

    @Synchronized
    fun setProviders(ctx: Context, list: List<ProviderAccount>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs(ctx).edit().putString(KEY_PROVIDERS, arr.toString()).apply()
    }

    fun upsertProvider(ctx: Context, account: ProviderAccount) {
        val list = providers(ctx).toMutableList()
        val index = list.indexOfFirst { it.id == account.id }
        if (index >= 0) list[index] = account else list.add(account)
        setProviders(ctx, list)
    }

    fun removeProvider(ctx: Context, id: String) {
        val list = providers(ctx).filterNot { it.id == id }
        setProviders(ctx, list)
        if (activeId(ctx) == id) setActiveId(ctx, list.firstOrNull()?.id ?: "")
    }

    fun setProviderEnabled(ctx: Context, id: String, enabled: Boolean) {
        val account = providers(ctx).firstOrNull { it.id == id } ?: return
        upsertProvider(ctx, account.copy(enabled = enabled))
    }

    fun enabledProviders(ctx: Context): List<ProviderAccount> = providers(ctx).filter { it.enabled }

    fun activeId(ctx: Context): String = prefs(ctx).getString(KEY_ACTIVE, "")!!

    fun setActiveId(ctx: Context, id: String) {
        prefs(ctx).edit().putString(KEY_ACTIVE, id).apply()
    }

    /** 当前用于聊天的账号：优先取「当前使用」；否则取第一个启用的账号。 */
    fun activeProvider(ctx: Context): ProviderAccount? {
        val list = providers(ctx)
        return list.firstOrNull { it.id == activeId(ctx) && it.enabled }
            ?: list.firstOrNull { it.enabled }
            ?: list.firstOrNull()
    }

    /** 用于文生图的账号：当前账号若已配图则优先，否则取第一个启用且配图的账号。 */
    fun imageProvider(ctx: Context): ProviderAccount? {
        val list = providers(ctx)
        val active = activeProvider(ctx)
        if (active != null && active.enabled && active.hasImage) return active
        return list.firstOrNull { it.enabled && it.hasImage }
    }

    fun newAccountId(): String = UUID.randomUUID().toString()

    fun accountFromTemplate(tpl: ProviderTemplate): ProviderAccount = ProviderAccount(
        id = newAccountId(),
        name = tpl.name,
        protocol = tpl.protocol,
        baseUrl = tpl.baseUrl,
        apiKey = "",
        enabled = false,
        model = tpl.model,
        supportsReasoningEffort = tpl.supportsReasoningEffort,
        imageEnabled = tpl.imageModel.isNotBlank(),
        imageModel = tpl.imageModel,
    )

    // ==================== 兼容访问器（取当前账号） ====================

    fun apiKey(ctx: Context): String = activeProvider(ctx)?.apiKey ?: ""
    fun baseUrl(ctx: Context): String = activeProvider(ctx)?.baseUrl ?: DEFAULT_BASE
    fun model(ctx: Context): String = activeProvider(ctx)?.model ?: DEFAULT_MODEL

    // ==================== 思考深度 / 步数 / 提示词 ====================

    fun thinkingDepth(ctx: Context): ThinkingDepth =
        ThinkingDepth.from(prefs(ctx).getString(KEY_DEPTH, ThinkingDepth.STANDARD.key))

    fun setThinkingDepth(ctx: Context, depth: ThinkingDepth) {
        prefs(ctx).edit().putString(KEY_DEPTH, depth.key).apply()
    }

    fun maxSteps(ctx: Context): Int = prefs(ctx).getInt(KEY_MAX_STEPS, DEFAULT_MAX_STEPS)

    fun setMaxSteps(ctx: Context, steps: Int) {
        prefs(ctx).edit().putInt(KEY_MAX_STEPS, steps).apply()
    }

    fun systemPrompt(ctx: Context): String = prefs(ctx).getString(KEY_SYSTEM_PROMPT, "")!!

    fun setSystemPrompt(ctx: Context, value: String) {
        prefs(ctx).edit().putString(KEY_SYSTEM_PROMPT, value).apply()
    }

    // ==================== 内部：解析与旧数据迁移 ====================

    private fun parseProviders(raw: String): List<ProviderAccount> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { ProviderAccount.fromJson(it) }
            }
        }.getOrDefault(emptyList())
    }

    private fun migrateLegacy(ctx: Context): List<ProviderAccount> {
        val p = prefs(ctx)
        val api = p.getString(LEGACY_API, "") ?: ""
        val base = p.getString(LEGACY_BASE, DEFAULT_BASE) ?: DEFAULT_BASE
        val model = p.getString(LEGACY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        val imageModel = p.getString(LEGACY_IMAGE_MODEL, "") ?: ""
        return listOf(
            ProviderAccount(
                id = newAccountId(),
                name = "DeepSeek",
                protocol = ProviderProtocol.OPENAI,
                baseUrl = base.ifBlank { DEFAULT_BASE },
                apiKey = api,
                enabled = true,
                model = model.ifBlank { DEFAULT_MODEL },
                supportsReasoningEffort = true,
                imageEnabled = imageModel.isNotBlank(),
                imageModel = imageModel,
            )
        )
    }
}

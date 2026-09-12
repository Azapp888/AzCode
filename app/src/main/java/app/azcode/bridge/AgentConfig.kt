package app.azcode.bridge

import android.content.Context

/**
 * Agent 配置：API Key / Base URL / 模型 / 最大步数。
 * 存于应用私有 SharedPreferences。Key 由用户自行填写，应用不读取任何环境变量。
 */
object AgentConfig {

    private const val PREFS = "azcode_agent"
    private const val KEY_API = "api_key"
    private const val KEY_BASE = "base_url"
    private const val KEY_MODEL = "model"
    private const val KEY_MAX_STEPS = "max_steps"

    const val DEFAULT_BASE = "https://api.deepseek.com/v1"
    const val DEFAULT_MODEL = "deepseek-chat"
    const val DEFAULT_MAX_STEPS = 25

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun apiKey(ctx: Context): String = prefs(ctx).getString(KEY_API, "")!!
    fun baseUrl(ctx: Context): String = prefs(ctx).getString(KEY_BASE, DEFAULT_BASE)!!
    fun model(ctx: Context): String = prefs(ctx).getString(KEY_MODEL, DEFAULT_MODEL)!!
    fun maxSteps(ctx: Context): Int = prefs(ctx).getInt(KEY_MAX_STEPS, DEFAULT_MAX_STEPS)

    fun save(ctx: Context, apiKey: String, baseUrl: String, model: String, maxSteps: Int) {
        prefs(ctx).edit()
            .putString(KEY_API, apiKey)
            .putString(KEY_BASE, baseUrl)
            .putString(KEY_MODEL, model)
            .putInt(KEY_MAX_STEPS, maxSteps)
            .apply()
    }
}

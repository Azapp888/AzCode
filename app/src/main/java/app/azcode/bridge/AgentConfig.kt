package app.azcode.bridge

import android.content.Context

/**
 * Agent 配置：API Key / Base URL / 模型 / 最大步数 / 自定义系统提示词。
 * 存于应用私有 SharedPreferences。Key 由用户自行填写，应用不读取任何环境变量。
 */
object AgentConfig {

    private const val PREFS = "azcode_agent"
    private const val KEY_API = "api_key"
    private const val KEY_BASE = "base_url"
    private const val KEY_MODEL = "model"
    private const val KEY_MAX_STEPS = "max_steps"
    private const val KEY_SYSTEM_PROMPT = "system_prompt"

    const val DEFAULT_BASE = "https://api.deepseek.com/v1"
    const val DEFAULT_MODEL = "deepseek-flash"
    const val DEFAULT_MAX_STEPS = 25

    /** 内置默认系统提示词；用户留空时使用。 */
    const val DEFAULT_SYSTEM_PROMPT = """你是 AzCode，一个运行在 Android 手机本地的自动化助手，直接操作用户的手机。
每一步先调用 get_screen 观察当前界面，再选择动作。坐标使用屏幕物理像素。
优先按文本点击（tap 的 text 字段）以提高鲁棒性；无法定位文本时再用坐标。
执行 shell 前确认任务确实需要；NORMAL 模式下 shell 会失败，此时改用无障碍能力。
面向用户的文字要简洁、口语化，直接说明你正在做什么或最终结果，不要输出 JSON、代码块或工具参数。
任务完成或无法继续时，调用 finish，并在 summary 里用一两句话向用户总结结果。"""

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun apiKey(ctx: Context): String = prefs(ctx).getString(KEY_API, "")!!
    fun baseUrl(ctx: Context): String = prefs(ctx).getString(KEY_BASE, DEFAULT_BASE)!!
    fun model(ctx: Context): String = prefs(ctx).getString(KEY_MODEL, DEFAULT_MODEL)!!
    fun maxSteps(ctx: Context): Int = prefs(ctx).getInt(KEY_MAX_STEPS, DEFAULT_MAX_STEPS)
    fun systemPrompt(ctx: Context): String = prefs(ctx).getString(KEY_SYSTEM_PROMPT, "")!!
    fun setSystemPrompt(ctx: Context, value: String) {
        prefs(ctx).edit().putString(KEY_SYSTEM_PROMPT, value).apply()
    }

    fun save(ctx: Context, apiKey: String, baseUrl: String, model: String, maxSteps: Int) {
        prefs(ctx).edit()
            .putString(KEY_API, apiKey)
            .putString(KEY_BASE, baseUrl)
            .putString(KEY_MODEL, model)
            .putInt(KEY_MAX_STEPS, maxSteps)
            .apply()
    }
}

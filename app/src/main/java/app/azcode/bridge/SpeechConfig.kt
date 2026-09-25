package app.azcode.bridge

import android.content.Context

/**
 * 语音识别偏好与讯飞凭据。
 *
 * 引擎二选一：`ENGINE_SYSTEM`（系统内置，离线优先）与 `ENGINE_XUNFEI`（讯飞开放平台在线识别）。
 * 讯飞凭据内置为常量，供所有用户直接使用；更换密钥需修改此处并重新发版。
 */
object SpeechConfig {

    const val ENGINE_SYSTEM = "system"
    const val ENGINE_XUNFEI = "xunfei"

    /**
     * 讯飞开放平台「语音听写」应用凭据。
     * 由项目所有者提供，内置到应用中；留空时讯飞引擎在设置页不可选，避免运行时才失败。
     */
    const val XUNFEI_APP_ID = ""
    const val XUNFEI_API_KEY = ""
    const val XUNFEI_API_SECRET = ""

    private const val PREFS = "azcode_speech"
    private const val KEY_ENGINE = "engine"

    fun engine(ctx: Context): String {
        val value = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ENGINE, null)
        return if (value == ENGINE_XUNFEI && xunfeiConfigured()) ENGINE_XUNFEI else ENGINE_SYSTEM
    }

    fun setEngine(ctx: Context, value: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ENGINE, if (value == ENGINE_XUNFEI) ENGINE_XUNFEI else ENGINE_SYSTEM)
            .apply()
    }

    fun xunfeiConfigured(): Boolean =
        XUNFEI_APP_ID.isNotBlank() && XUNFEI_API_KEY.isNotBlank() && XUNFEI_API_SECRET.isNotBlank()
}
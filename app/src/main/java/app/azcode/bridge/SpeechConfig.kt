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
     * 真实值由 CI 通过 GitHub Actions Secrets 注入到 [SpeechSecrets]，仓库内保持空值；
     * 留空时讯飞引擎在设置页不可选，避免运行时才失败。
     */
    val XUNFEI_APP_ID: String get() = SpeechSecrets.XUNFEI_APP_ID
    val XUNFEI_API_KEY: String get() = SpeechSecrets.XUNFEI_API_KEY
    val XUNFEI_API_SECRET: String get() = SpeechSecrets.XUNFEI_API_SECRET

    private const val PREFS = "azcode_speech"
    private const val KEY_ENGINE = "engine"

    fun engine(ctx: Context): String {
        val value = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ENGINE, null)
        return when {
            value == ENGINE_SYSTEM -> ENGINE_SYSTEM
            value == ENGINE_XUNFEI && xunfeiConfigured() -> ENGINE_XUNFEI
            // 未显式选择时，内置了讯飞密钥就默认用讯飞在线识别（中文更准），否则用系统内置。
            value == null && xunfeiConfigured() -> ENGINE_XUNFEI
            else -> ENGINE_SYSTEM
        }
    }

    fun setEngine(ctx: Context, value: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ENGINE, if (value == ENGINE_XUNFEI) ENGINE_XUNFEI else ENGINE_SYSTEM)
            .apply()
    }

    fun xunfeiConfigured(): Boolean =
        XUNFEI_APP_ID.isNotBlank() && XUNFEI_API_KEY.isNotBlank() && XUNFEI_API_SECRET.isNotBlank()
}
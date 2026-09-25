package app.azcode.bridge

import android.content.Context

/**
 * 讯飞开放平台「语音听写」在线识别。
 *
 * 需要 [SpeechConfig] 中内置 APPID / APIKey / APISecret 才能工作；未配置时 [isAvailable] 返回 false，
 * 设置页也不允许选中该引擎，避免运行到一半才失败。
 *
 * 网络实现（WebSocket 或 HTTP 一句话识别）将在拿到密钥后接入，并对错误做兜底提示，
 * 识别失败时回退提示用户改用系统内置引擎。
 */
class XunfeiSpeechEngine : SpeechEngine {

    override fun isAvailable(ctx: Context): Boolean = SpeechConfig.xunfeiConfigured()

    override fun start(ctx: Context, listener: SpeechEngine.Listener) {
        listener.onStateChanged(false)
        if (!SpeechConfig.xunfeiConfigured()) {
            listener.onError("讯飞语音尚未内置密钥，请在「设置 → 语音输入」改用系统内置")
            return
        }
        listener.onError("讯飞语音识别正在接入中")
    }

    override fun stop() {}

    override fun cancel() {}

    override fun release() {}
}
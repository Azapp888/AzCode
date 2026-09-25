package app.azcode.bridge

import android.content.Context

/**
 * 语音识别引擎抽象：把识别结果以「增量文本」形式回吐给 UI，由 UI 决定如何拼接。
 *
 * 目前有两种实现：
 *  - [SystemSpeechEngine]：调用系统识别服务（Android 12+ 优先设备端离线识别）；
 *  - [XunfeiSpeechEngine]：调用讯飞开放平台在线识别（凭据由 [SpeechConfig] 内置）。
 *
 * 用户在「设置 → 语音输入」中二选一，[SpeechEngines.create] 按偏好创建实例。
 */
interface SpeechEngine {

    /** 当前设备/配置下该引擎是否可用。 */
    fun isAvailable(ctx: Context): Boolean

    /** 开始一次识别。回调可能来自任意线程，UI 侧需自行切回主线程。 */
    fun start(ctx: Context, listener: Listener)

    /** 请求正常结束（把已识别内容作为最终结果返回）。 */
    fun stop()

    /** 立即取消，不返回结果。 */
    fun cancel()

    /** 释放底层资源，Activity 销毁时调用。 */
    fun release()

    interface Listener {
        /** 实时中间结果，可反复回调。 */
        fun onPartial(text: String)

        /** 最终识别结果。 */
        fun onResult(text: String)

        /** 出错信息，已本地化。 */
        fun onError(message: String)

        /** 识别开始/结束状态变化，用于切换录音按钮外观。 */
        fun onStateChanged(listening: Boolean)
    }
}

object SpeechEngines {
    fun create(ctx: Context): SpeechEngine =
        if (SpeechConfig.engine(ctx) == SpeechConfig.ENGINE_XUNFEI) {
            XunfeiSpeechEngine()
        } else {
            SystemSpeechEngine()
        }

    /** 供设置页展示当前引擎名称。 */
    fun engineLabel(ctx: Context): String =
        if (SpeechConfig.engine(ctx) == SpeechConfig.ENGINE_XUNFEI) {
            if (SpeechConfig.xunfeiConfigured()) "讯飞星火" else "讯飞星火（未配置）"
        } else {
            "系统内置"
        }

    /** 设置页允许选择的引擎；讯飞未内置密钥前不可选中。 */
    fun canSelectXunfei(): Boolean = SpeechConfig.xunfeiConfigured()
}
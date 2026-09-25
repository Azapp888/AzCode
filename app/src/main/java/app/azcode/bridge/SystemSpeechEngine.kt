package app.azcode.bridge

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * 系统内置语音识别。
 *
 * 优先使用设备端离线识别（Android 12 / API 31+），无法离线时回退到系统识别服务（可能联网）。
 * 不依赖任何第三方 SDK 与密钥，但中文识别能力因设备/系统而异。
 */
class SystemSpeechEngine : SpeechEngine {

    private var recognizer: SpeechRecognizer? = null
    private var listener: SpeechEngine.Listener? = null
    private var lastPartial = ""

    override fun isAvailable(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 31) {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx) ||
                SpeechRecognizer.isRecognitionAvailable(ctx)
        } else {
            SpeechRecognizer.isRecognitionAvailable(ctx)
        }

    override fun start(ctx: Context, listener: SpeechEngine.Listener) {
        this.listener = listener
        lastPartial = ""
        val rec = try {
            recognizer ?: create(ctx).also { recognizer = it }
        } catch (e: Exception) {
            listener.onError(e.message ?: "当前设备不支持语音识别")
            listener.onStateChanged(false)
            return
        }
        listener.onStateChanged(true)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        runCatching { rec.startListening(intent) }.onFailure {
            listener.onError(it.message ?: "启动语音识别失败")
            listener.onStateChanged(false)
        }
    }

    private fun create(ctx: Context): SpeechRecognizer {
        val rec = if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
        } else {
            SpeechRecognizer.createSpeechRecognizer(ctx)
        }
        rec.setRecognitionListener(recognitionListener)
        return rec
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            listener?.onError(errorText(error))
            listener?.onStateChanged(false)
        }

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            listener?.onResult(text)
            listener?.onStateChanged(false)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            if (text.isNotBlank() && text != lastPartial) {
                lastPartial = text
                listener?.onPartial(text)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    override fun stop() {
        runCatching { recognizer?.stopListening() }
    }

    override fun cancel() {
        runCatching { recognizer?.cancel() }
        listener?.onStateChanged(false)
    }

    override fun release() {
        runCatching { recognizer?.destroy() }
        recognizer = null
        listener = null
    }

    private fun errorText(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "录音错误"
        SpeechRecognizer.ERROR_CLIENT -> "识别客户端错误"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少麦克风权限"
        SpeechRecognizer.ERROR_NETWORK -> "网络错误"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
        SpeechRecognizer.ERROR_NO_MATCH -> "没有识别到内容"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别服务忙，请稍后再试"
        SpeechRecognizer.ERROR_SERVER -> "识别服务错误"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "未检测到语音"
        else -> "语音识别失败($error)"
    }
}
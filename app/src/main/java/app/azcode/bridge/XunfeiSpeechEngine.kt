package app.azcode.bridge

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 讯飞开放平台「语音听写」（IAT）在线识别。
 *
 * 走 WebSocket 流式协议：鉴权 URL 由 APISecret 做 HMAC-SHA256 签名生成，随后把 16k/16bit 单声道
 * PCM 以 40ms 为一帧持续上行，边识别边回吐中间结果。凭据来自 [SpeechConfig]（CI 注入），
 * 未配置时 [isAvailable] 返回 false 且设置页不可选中。
 *
 * 识别失败会给出本地化提示，用户可在「设置 → 语音输入」改回系统内置引擎。
 */
class XunfeiSpeechEngine : SpeechEngine {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var recordThread: Thread? = null
    private var listener: SpeechEngine.Listener? = null
    private val resultBuffer = StringBuilder()

    @Volatile private var cancelled = false
    @Volatile private var stopping = false

    override fun isAvailable(ctx: Context): Boolean = SpeechConfig.xunfeiConfigured()

    @SuppressLint("MissingPermission")
    override fun start(ctx: Context, listener: SpeechEngine.Listener) {
        this.listener = listener
        cancelled = false
        stopping = false
        resultBuffer.setLength(0)
        if (!SpeechConfig.xunfeiConfigured()) {
            listener.onError("讯飞语音尚未内置密钥，请在「设置 → 语音输入」改用系统内置")
            listener.onStateChanged(false)
            return
        }
        val url = try {
            authUrl()
        } catch (e: Exception) {
            listener.onError("讯飞鉴权失败：${e.message}")
            listener.onStateChanged(false)
            return
        }
        listener.onStateChanged(true)
        webSocket = client.newWebSocket(Request.Builder().url(url).build(), socketListener)
    }

    @SuppressLint("MissingPermission")
    private fun startAudio(ws: WebSocket) {
        val sampleRate = 16000
        val frameBytes = 1280
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, frameBytes * 4)
            )
        } catch (e: Exception) {
            listener?.onError("无法打开麦克风：${e.message}")
            listener?.onStateChanged(false)
            closeQuietly()
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { rec.release() }
            listener?.onError("麦克风初始化失败，请检查录音权限")
            listener?.onStateChanged(false)
            closeQuietly()
            return
        }
        audioRecord = rec
        rec.startRecording()
        recordThread = Thread {
            var first = true
            val buffer = ByteArray(frameBytes)
            try {
                while (!cancelled && !stopping) {
                    var read = 0
                    while (read < frameBytes) {
                        val n = rec.read(buffer, read, frameBytes - read)
                        if (n <= 0) break
                        read += n
                    }
                    if (cancelled || stopping) break
                    val status = if (first) 0 else 1
                    ws.send(frameJson(status, buffer))
                    first = false
                }
                if (!cancelled) {
                    ws.send(frameJson(2, ByteArray(0)))
                }
            } catch (e: Exception) {
                if (!cancelled) {
                    listener?.onError("音频上行失败：${e.message}")
                    listener?.onStateChanged(false)
                }
            } finally {
                runCatching { rec.stop() }
                runCatching { rec.release() }
            }
        }.also { it.start() }
    }

    private val socketListener = object : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            startAudio(ws)
        }

        override fun onMessage(ws: WebSocket, text: String) {
            handleMessage(text)
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            if (!cancelled) {
                listener?.onError(t.message ?: "讯飞语音连接失败")
                listener?.onStateChanged(false)
            }
            closeQuietly()
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            stopAudio()
            if (!cancelled) listener?.onStateChanged(false)
        }
    }

    private fun handleMessage(text: String) {
        val obj = try {
            JSONObject(text)
        } catch (e: Exception) {
            return
        }
        val code = obj.optInt("code", -1)
        if (code != 0) {
            listener?.onError(obj.optString("message", "讯飞识别失败($code)"))
            listener?.onStateChanged(false)
            cancelled = true
            closeQuietly()
            return
        }
        val data = obj.optJSONObject("data") ?: return
        data.optJSONObject("result")?.let { appendResult(it) }
        if (resultBuffer.isNotEmpty()) listener?.onPartial(resultBuffer.toString())
        if (data.optInt("status", -1) == 2) {
            listener?.onResult(resultBuffer.toString())
            listener?.onStateChanged(false)
            cancelled = true
            closeQuietly()
        }
    }

    /** 非动态修正模式下，每帧只回吐新增词，直接按序拼接即可。 */
    private fun appendResult(result: JSONObject) {
        val ws = result.optJSONArray("ws") ?: return
        for (i in 0 until ws.length()) {
            val cw = ws.optJSONObject(i)?.optJSONArray("cw") ?: continue
            if (cw.length() > 0) resultBuffer.append(cw.optJSONObject(0)?.optString("w").orEmpty())
        }
    }

    private fun frameJson(status: Int, audio: ByteArray): String {
        val payload = JSONObject()
            .put("status", status)
            .put("format", "audio/L16;rate=16000")
            .put("encoding", "raw")
            .put("audio", Base64.encodeToString(audio, Base64.NO_WRAP))
        val frame = JSONObject().put("data", payload)
        if (status == 0) {
            frame.put("common", JSONObject().put("app_id", SpeechConfig.XUNFEI_APP_ID))
            frame.put(
                "business",
                JSONObject()
                    .put("language", "zh_cn")
                    .put("domain", "iat")
                    .put("accent", "mandarin")
                    .put("vad_eos", 10000)
            )
        }
        return frame.toString()
    }

    private fun authUrl(): String {
        val host = "iat-api.xfyun.cn"
        val path = "/v2/iat"
        val date = httpDate()
        val origin = "host: $host\ndate: $date\nGET $path HTTP/1.1"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(SpeechConfig.XUNFEI_API_SECRET.toByteArray(), "HmacSHA256"))
        val signature = Base64.encodeToString(mac.doFinal(origin.toByteArray()), Base64.NO_WRAP)
        val authOrigin = "api_key=\"${SpeechConfig.XUNFEI_API_KEY}\", " +
            "algorithm=\"hmac-sha256\", headers=\"host date request-line\", signature=\"$signature\""
        val authorization = Base64.encodeToString(authOrigin.toByteArray(), Base64.NO_WRAP)
        return "wss://$host$path?authorization=$authorization" +
            "&date=${URLEncoder.encode(date, "UTF-8")}&host=$host"
    }

    private fun httpDate(): String {
        val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("GMT")
        return fmt.format(Date())
    }

    override fun stop() {
        stopping = true
    }

    override fun cancel() {
        cancelled = true
        listener?.onStateChanged(false)
        closeQuietly()
    }

    override fun release() {
        cancelled = true
        closeQuietly()
        listener = null
        runCatching { client.dispatcher.executorService.shutdown() }
    }

    private fun stopAudio() {
        runCatching { audioRecord?.stop() }
    }

    private fun closeQuietly() {
        stopAudio()
        runCatching { webSocket?.close(1000, null) }
        webSocket = null
        recordThread = null
    }
}
package app.azcode.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast

/**
 * 悬浮语音助理：在当前前台页面之上叠加一层"透明背景 + 呼吸蓝光"的光晕，而不是切到本应用界面。
 *
 * 依赖「显示在其他应用上层」（SYSTEM_ALERT_WINDOW）权限，通过两个 TYPE_APPLICATION_OVERLAY 窗口实现：
 *  - 全屏透明窗口：只绘制光晕/实时文字/波动条，设置 FLAG_NOT_TOUCHABLE，触摸事件照常落到下层应用；
 *  - 右上角小窗口：仅承载关闭按钮，可点击，不遮挡其它区域。
 *
 * 说完停顿约 [VAD_EOS] 毫秒自动判定"说完了"并提交；提交时把文本带回主界面执行。
 * 全程无声 [SILENCE_NO_SPEECH]、总时长 [MAX_LISTEN] 后自动收起。
 */
class AssistOverlayService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager

    private var glowView: View? = null
    private var closeView: View? = null
    private var orb: AssistOrbView? = null
    private var wave: VoiceWaveView? = null
    private var tvText: TextView? = null

    private var engine: SpeechEngine? = null
    private var startedAt = 0L
    private var lastSpeechAt = 0L
    private var gotSpeech = false
    private var done = false

    private val watchdog = object : Runnable {
        override fun run() {
            if (done) return
            val now = SystemClock.elapsedRealtime()
            when {
                gotSpeech && now - lastSpeechAt >= SILENCE_AFTER_SPEECH -> submit()
                !gotSpeech && now - startedAt >= SILENCE_NO_SPEECH -> dismiss()
                now - startedAt >= MAX_LISTEN -> submit()
                else -> handler.postDelayed(this, WATCHDOG_TICK)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        handler.post {
            if (glowView == null) attachOverlay()
            restartListening()
        }
        return START_NOT_STICKY
    }

    /** 每次唤起都重新开始一次识别（重复唤起时复用已有窗口）。 */
    private fun restartListening() {
        done = false
        gotSpeech = false
        tvText?.text = getString(R.string.assist_listening)
        runCatching { engine?.release() }
        val e = SpeechEngines.create(this, vadEosMillis = VAD_EOS)
        engine = e
        startedAt = SystemClock.elapsedRealtime()
        lastSpeechAt = startedAt
        if (!e.isAvailable(this)) {
            toast(getString(R.string.warn_mic_unavailable))
            dismiss()
            return
        }
        e.start(this, listener)
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, WATCHDOG_TICK)
    }

    private fun attachOverlay() {
        val inflater = LayoutInflater.from(this)

        val glow = inflater.inflate(R.layout.view_assist_overlay, null)
        orb = glow.findViewById(R.id.overlayOrb)
        wave = glow.findViewById(R.id.overlayWave)
        tvText = glow.findViewById(R.id.tvOverlayText)
        glowView = glow
        orb?.start()
        wave?.start()
        runCatching { windowManager.addView(glow, glowParams()) }
            .onFailure { CrashLog.e(TAG, "添加悬浮光晕失败", it) }

        val close = inflater.inflate(R.layout.view_assist_close, null)
        close.findViewById<View>(R.id.btnOverlayClose).setOnClickListener { dismiss() }
        closeView = close
        runCatching { windowManager.addView(close, closeParams()) }
            .onFailure { CrashLog.e(TAG, "添加悬浮关闭按钮失败", it) }
    }

    private fun glowParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun closeParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(10)
            y = dp(38)
        }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private val listener = object : SpeechEngine.Listener {
        override fun onPartial(text: String) {
            if (text.isBlank()) return
            handler.post {
                if (done) return@post
                tvText?.text = text
                gotSpeech = true
                lastSpeechAt = SystemClock.elapsedRealtime()
                val level = 0.62f + 0.34f * ((SystemClock.elapsedRealtime() % 320L) / 320f)
                orb?.setLevel(level)
                wave?.setLevel(level)
            }
        }

        override fun onResult(text: String) {
            handler.post {
                if (text.isNotBlank()) tvText?.text = text
                submit()
            }
        }

        override fun onError(message: String) {
            handler.post { if (gotSpeech) submit() else dismiss() }
        }

        override fun onStateChanged(listening: Boolean) {}
    }

    /** 提交识别文本，拉起主界面执行任务。 */
    private fun submit() {
        if (done) return
        done = true
        handler.removeCallbacks(watchdog)
        val text = tvText?.text?.toString()?.trim().orEmpty()
        val placeholder = getString(R.string.assist_listening)
        runCatching { engine?.stop() }
        runCatching { engine?.release() }
        engine = null
        if (text.isEmpty() || text == placeholder) {
            dismiss()
            return
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_AUTO_TASK, text)
        }
        runCatching { startActivity(intent) }
            .onFailure { CrashLog.e(TAG, "拉起主界面失败", it) }
        teardownOverlay()
        stopSelf()
    }

    /** 收起光晕并结束服务（不提交任何内容）。 */
    private fun dismiss() {
        if (done) return
        done = true
        handler.removeCallbacks(watchdog)
        runCatching { engine?.cancel() }
        runCatching { engine?.release() }
        engine = null
        teardownOverlay()
        stopSelf()
    }

    private fun teardownOverlay() {
        runCatching { orb?.stop() }
        runCatching { wave?.stop() }
        glowView?.let { runCatching { windowManager.removeView(it) } }
        closeView?.let { runCatching { windowManager.removeView(it) } }
        glowView = null
        closeView = null
    }

    override fun onDestroy() {
        done = true
        handler.removeCallbacks(watchdog)
        runCatching { engine?.release() }
        engine = null
        teardownOverlay()
        super.onDestroy()
    }

    private fun startAsForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.assist_overlay_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pending = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            ),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(getString(R.string.assist_overlay_notif_title))
            .setContentText(getString(R.string.assist_overlay_notif_text))
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
    }

    private fun toast(message: String) {
        if (message.isBlank()) return
        runCatching { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "AssistOverlay"
        private const val CHANNEL_ID = "azcode_assist_overlay"
        private const val NOTIF_ID = 8849

        /** 说完后的静音判定阈值（毫秒）：约 1.5 秒停顿即认定说完了。 */
        private const val VAD_EOS = 1500
        private const val SILENCE_AFTER_SPEECH = 1700L
        private const val SILENCE_NO_SPEECH = 6000L
        private const val MAX_LISTEN = 60_000L
        private const val WATCHDOG_TICK = 150L

        /** 是否已获得「显示在其他应用上层」权限。 */
        fun canOverlay(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || android.provider.Settings.canDrawOverlays(context)

        fun start(context: Context) {
            context.startForegroundService(Intent(context, AssistOverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AssistOverlayService::class.java))
        }
    }
}
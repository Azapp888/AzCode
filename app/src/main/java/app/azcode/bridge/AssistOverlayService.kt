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
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 悬浮语音助理：在当前前台页面之上叠加"透明背景 + 呼吸蓝光 + 左下角悬浮气泡"，
 * 不切换到本应用界面，识别与任务执行都在当前页面完成。
 *
 * 依赖「显示在其他应用上层」（SYSTEM_ALERT_WINDOW）权限，通过两个 OVERLAY 窗口实现：
 *  - 全屏透明窗口：绘制边缘呼吸光 + 左下面板，设置 FLAG_NOT_TOUCHABLE，触摸照常落到下层应用；
 *  - 右上角小窗口：仅承载关闭按钮，可点击，其余区域不遮挡。
 *
 * 流程：进入即聆听 → 说完停顿约 [VAD_EOS] 毫秒自动判定 → 直接在本服务内跑 [AgentRunner]
 * 把回复/进度实时写进气泡，无需跳回主界面；需要用户作答等复杂交互的场景仍由通知栏承接。
 */
class AssistOverlayService : Service() {

    private enum class Phase { LISTENING, PROCESSING, DONE }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager

    private var glowView: View? = null
    private var closeView: View? = null
    private var keyboardView: View? = null
    private var inputView: View? = null
    private var questionView: View? = null
    private var orb: AssistOrbView? = null
    private var wave: VoiceWaveView? = null
    private var tvText: TextView? = null
    private var tvState: TextView? = null

    private var engine: SpeechEngine? = null
    private var runner: AgentRunner? = null
    private var worker: Thread? = null
    private var questionLatch: CountDownLatch? = null

    private var phase = Phase.LISTENING
    private var runToken = 0
    private var startedAt = 0L
    private var lastSpeechAt = 0L
    private var gotSpeech = false

    private val watchdog = object : Runnable {
        override fun run() {
            if (phase != Phase.LISTENING) return
            val now = SystemClock.elapsedRealtime()
            when {
                gotSpeech && now - lastSpeechAt >= SILENCE_AFTER_SPEECH -> submitSpeech()
                !gotSpeech && now - startedAt >= SILENCE_NO_SPEECH -> dismiss()
                now - startedAt >= MAX_LISTEN -> submitSpeech()
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
        cancelRunningTask()
        runToken++
        phase = Phase.LISTENING
        gotSpeech = false
        startedAt = SystemClock.elapsedRealtime()
        lastSpeechAt = startedAt
        tvText?.text = ""
        tvText?.visibility = View.GONE
        tvState?.text = getString(R.string.assist_listening)
        wave?.visibility = View.VISIBLE
        wave?.start()
        orb?.start()

        runCatching { engine?.release() }
        val e = SpeechEngines.create(this, vadEosMillis = VAD_EOS)
        engine = e
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
        tvState = glow.findViewById(R.id.tvOverlayState)
        glowView = glow
        runCatching { windowManager.addView(glow, glowParams()) }
            .onFailure { CrashLog.e(TAG, "添加悬浮光晕失败", it) }

        val close = inflater.inflate(R.layout.view_assist_close, null)
        close.findViewById<View>(R.id.btnOverlayClose).setOnClickListener { dismiss() }
        closeView = close
        runCatching { windowManager.addView(close, closeParams()) }
            .onFailure { CrashLog.e(TAG, "添加悬浮关闭按钮失败", it) }

        val keyboard = inflater.inflate(R.layout.view_assist_keyboard, null)
        keyboard.findViewById<View>(R.id.btnOverlayKeyboard).setOnClickListener { toggleInputOverlay() }
        keyboardView = keyboard
        runCatching { windowManager.addView(keyboard, keyboardParams()) }
            .onFailure { CrashLog.e(TAG, "添加键盘气泡失败", it) }
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
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 覆盖整个物理屏幕：延伸到刘海/挖孔与系统栏区域，让蓝光贴着屏幕硬件边缘起亮。
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            if (Build.VERSION.SDK_INT >= 30) {
                fitInsetsTypes = 0
            }
        }

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

    private fun keyboardParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = dp(16)
            y = dp(28)
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
                if (phase != Phase.LISTENING) return@post
                tvText?.text = text
                tvText?.visibility = View.VISIBLE
                gotSpeech = true
                lastSpeechAt = SystemClock.elapsedRealtime()
                val level = 0.62f + 0.34f * ((SystemClock.elapsedRealtime() % 320L) / 320f)
                orb?.setLevel(level)
                wave?.setLevel(level)
            }
        }

        override fun onResult(text: String) {
            handler.post {
                if (text.isNotBlank()) {
                    tvText?.text = text
                    tvText?.visibility = View.VISIBLE
                }
                submitSpeech()
            }
        }

        override fun onError(message: String) {
            handler.post {
                if (phase != Phase.LISTENING) return@post
                if (gotSpeech) submitSpeech() else dismiss()
            }
        }

        override fun onStateChanged(listening: Boolean) {}
    }

    /** 结束聆听并把识别到的文本交给 Agent 在当前页面处理。 */
    private fun submitSpeech() {
        if (phase != Phase.LISTENING) return
        handler.removeCallbacks(watchdog)
        val text = tvText?.text?.toString()?.trim().orEmpty()
        val placeholder = getString(R.string.assist_listening)
        runCatching { engine?.stop() }
        runCatching { engine?.release() }
        engine = null
        wave?.stop()
        wave?.visibility = View.GONE
        if (text.isEmpty() || text == placeholder) {
            dismiss()
            return
        }
        CommandMemory.record(this, text)
        runTask(text)
    }

    /** 在本服务内直接运行 Agent，把过程与回复写进左下角气泡（不跳回主界面）。 */
    private fun runTask(task: String) {
        phase = Phase.PROCESSING
        tvState?.text = getString(R.string.assist_processing)

        if (AgentConfig.apiKey(this).isBlank() || AgentConfig.activeProvider(this) == null) {
            tvState?.text = getString(R.string.assist_failed)
            tvText?.text = getString(R.string.assist_no_model)
            tvText?.visibility = View.VISIBLE
            scheduleDismiss(5000)
            return
        }

        val session = SessionStore.current(this)
        val token = runToken
        val r = AgentRunner(
            applicationContext,
            askUser = { q -> askUserInPage(q) },
            // 助理自身已有边缘呼吸光，操作手机时不再叠加软件本体的光效。
            suppressSystemGlow = true,
        ) { event ->
            handler.post { if (token == runToken) renderEvent(event) }
        }
        runner = r
        worker = Thread({
            var failed = false
            try {
                r.run(task, emptyList(), session)
            } catch (e: Exception) {
                failed = true
                CrashLog.e(TAG, "悬浮助理任务异常", e)
                handler.post {
                    if (token == runToken && phase == Phase.PROCESSING) {
                        tvState?.text = getString(R.string.assist_failed)
                        tvText?.text = e.message ?: getString(R.string.assist_failed)
                        tvText?.visibility = View.VISIBLE
                    }
                }
            } finally {
                runCatching { SessionStore.save(this, session) }
                handler.post {
                    if (token == runToken && phase == Phase.PROCESSING) {
                        tvState?.text = if (failed) getString(R.string.assist_failed) else getString(R.string.assist_done)
                        scheduleDismiss(if (failed) 6000 else 4500)
                    }
                }
            }
        }, "azcode-assist-agent").apply { start() }
    }

    private fun renderEvent(event: AgentEvent) {
        if (phase != Phase.PROCESSING) return
        when (event) {
            is AgentEvent.Thinking -> tvState?.text = getString(R.string.assist_thinking)
            is AgentEvent.AssistantText -> {
                val text = AiOutput.textOnly(event.text) ?: event.text
                if (text.isNotBlank()) {
                    tvText?.text = text
                    tvText?.visibility = View.VISIBLE
                    tvState?.text = getString(R.string.assist_processing)
                }
            }
            is AgentEvent.ToolStart -> tvState?.text = event.name
            is AgentEvent.Failure -> {
                tvState?.text = getString(R.string.assist_failed)
                tvText?.text = event.message
                tvText?.visibility = View.VISIBLE
            }
            is AgentEvent.Notice -> {
                tvText?.text = event.text
                tvText?.visibility = View.VISIBLE
            }
            else -> {}
        }
    }

    private fun scheduleDismiss(delayMs: Long) {
        handler.postDelayed({
            teardownOverlay()
            stopSelf()
        }, delayMs)
    }

    // ==================== 向用户提问 ====================

    /**
     * 在当前页面弹出问答卡片（可点选项或手动输入），同时发送通知栏提醒作为并行作答通道。
     * 在工作线程上阻塞，返回 null 表示超时未作答或任务已取消。
     */
    private fun askUserInPage(question: AgentQuestion): String? {
        val latch = CountDownLatch(1)
        val answer = AtomicReference<String?>(null)
        questionLatch = latch
        runCatching { TaskNotifier.notifyQuestion(this, question) }
        QuestionBus.register { ans ->
            if (answer.compareAndSet(null, ans)) {
                handler.post { hideQuestionOverlay() }
                latch.countDown()
            }
        }
        handler.post {
            runCatching { showQuestionOverlay(question) { ans ->
                if (answer.compareAndSet(null, ans)) {
                    hideQuestionOverlay()
                    latch.countDown()
                }
            } }.onFailure { CrashLog.e(TAG, "显示问答卡片失败", it) }
        }
        val deadline = SystemClock.elapsedRealtime() + QUESTION_TIMEOUT
        while (latch.count > 0) {
            if (phase == Phase.DONE) break
            if (runCatching { latch.await(150, TimeUnit.MILLISECONDS) }.getOrDefault(false)) break
            if (SystemClock.elapsedRealtime() > deadline) break
        }
        QuestionBus.clear()
        runCatching { TaskNotifier.cancelQuestion(this) }
        handler.post { hideQuestionOverlay() }
        questionLatch = null
        return if (latch.count == 0L) answer.get() else null
    }

    private fun showQuestionOverlay(question: AgentQuestion, onAnswer: (String?) -> Unit) {
        if (questionView != null) hideQuestionOverlay()
        val view = LayoutInflater.from(this).inflate(R.layout.view_assist_question, null)
        view.findViewById<TextView>(R.id.tvAskQuestion).text = question.question
        val options = view.findViewById<LinearLayout>(R.id.askOptions)
        val input = view.findViewById<EditText>(R.id.etAskAnswer)
        input.visibility = if (question.allowCustom) View.VISIBLE else View.GONE
        options.removeAllViews()
        question.options.forEach { option ->
            val item = TextView(this).apply {
                text = option
                textSize = 14f
                setTextColor(0xFFEAF1FF.toInt())
                background = getDrawable(R.drawable.bg_assist_option)
                setPadding(dp(14), dp(9), dp(14), dp(9))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(8) }
                setOnClickListener { onAnswer(option) }
            }
            options.addView(item)
        }
        view.findViewById<TextView>(R.id.btnAskCancel).setOnClickListener { onAnswer(null) }
        view.findViewById<TextView>(R.id.btnAskSend).setOnClickListener {
            val text = input.text?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) onAnswer(text)
        }
        questionView = view
        windowManager.addView(view, questionParams())
    }

    private fun hideQuestionOverlay() {
        questionView?.let { runCatching { windowManager.removeView(it) } }
        questionView = null
    }

    private fun questionParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = dp(16)
            y = dp(96)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

    // ==================== 键盘输入 ====================

    /** 右下角键盘气泡：展开 / 收起底部输入条。 */
    private fun toggleInputOverlay() {
        if (inputView != null) hideInputOverlay() else showInputOverlay()
    }

    private fun showInputOverlay() {
        if (inputView != null) return
        val view = LayoutInflater.from(this).inflate(R.layout.view_assist_input, null)
        val input = view.findViewById<EditText>(R.id.etAssistInput)
        view.findViewById<TextView>(R.id.btnAssistSend).setOnClickListener {
            val text = input.text?.toString()?.trim().orEmpty()
            hideInputOverlay()
            if (text.isNotEmpty()) submitTyped(text)
        }
        inputView = view
        runCatching { windowManager.addView(view, inputParams()) }
            .onFailure {
                CrashLog.e(TAG, "显示键盘输入条失败", it)
                inputView = null
                return
            }
        input.post {
            input.requestFocus()
            runCatching {
                (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun hideInputOverlay() {
        val view = inputView ?: return
        inputView = null
        runCatching {
            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(view.windowToken, 0)
        }
        runCatching { windowManager.removeView(view) }
    }

    /** 把键盘输入的指令当作一次识别结果提交，与语音链路共用同一套执行流程。 */
    private fun submitTyped(text: String) {
        handler.removeCallbacks(watchdog)
        runCatching { engine?.stop() }
        runCatching { engine?.release() }
        engine = null
        wave?.stop()
        wave?.visibility = View.GONE
        cancelRunningTask()
        CommandMemory.record(this, text)
        tvText?.text = text
        tvText?.visibility = View.VISIBLE
        runTask(text)
    }

    private fun inputParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        }

    private fun cancelRunningTask() {
        runCatching { runner?.cancel() }
        runner = null
        worker = null
        runToken++
    }

    /** 收起光晕并结束服务。 */
    private fun dismiss() {
        phase = Phase.DONE
        handler.removeCallbacks(watchdog)
        handler.removeCallbacksAndMessages(null)
        cancelRunningTask()
        runCatching { engine?.cancel() }
        runCatching { engine?.release() }
        engine = null
        teardownOverlay()
        stopSelf()
    }

    private fun teardownOverlay() {
        runCatching { orb?.stop() }
        runCatching { wave?.stop() }
        hideQuestionOverlay()
        hideInputOverlay()
        glowView?.let { runCatching { windowManager.removeView(it) } }
        closeView?.let { runCatching { windowManager.removeView(it) } }
        keyboardView?.let { runCatching { windowManager.removeView(it) } }
        glowView = null
        closeView = null
        keyboardView = null
    }

    override fun onDestroy() {
        phase = Phase.DONE
        handler.removeCallbacksAndMessages(null)
        cancelRunningTask()
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
        /** 等待用户作答的超时（毫秒），超时按未作答返回给模型。 */
        private const val QUESTION_TIMEOUT = 120_000L

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
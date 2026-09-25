package app.azcode.bridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 全屏语音助理界面：系统助理唤起（长按电源键/耳机、助理手势）时进入。
 *
 * 交互：
 *  - 进入即开始识别，屏幕中央大字号实时显示识别内容；
 *  - 用户说完停顿约 [VAD_EOS] 毫秒后由引擎判定「说完了」，自动提交并回到主界面执行；
 *  - 兜底看门狗：说了一小段后停顿时长超过 [SILENCE_AFTER_SPEECH] 也会提交；
 *    全程没说话、超过 [SILENCE_NO_SPEECH] 则安静退出；
 *    总时长超过 [MAX_LISTEN] 强制提交，避免长时间占用麦克风。
 *
 * 识别到的文本通过 [MainActivity.EXTRA_AUTO_TASK] 带回主界面，由主界面直接发送为任务。
 */
class AssistActivity : AppCompatActivity() {

    private lateinit var orb: AssistOrbView
    private lateinit var wave: VoiceWaveView
    private lateinit var tvText: TextView

    private var engine: SpeechEngine? = null
    private var startedAt = 0L
    private var lastSpeechAt = 0L
    private var gotSpeech = false
    private var submitted = false

    private val handler = Handler(Looper.getMainLooper())

    private val watchdog = object : Runnable {
        override fun run() {
            if (submitted || isFinishing) return
            val now = SystemClock.elapsedRealtime()
            when {
                gotSpeech && now - lastSpeechAt >= SILENCE_AFTER_SPEECH -> submit()
                !gotSpeech && now - startedAt >= SILENCE_NO_SPEECH -> finishQuietly()
                now - startedAt >= MAX_LISTEN -> submit()
                else -> handler.postDelayed(this, WATCHDOG_TICK)
            }
        }
    }

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            beginListening()
        } else {
            toast(getString(R.string.warn_need_mic))
            finishQuietly()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_assist)
        applyFullScreen()

        orb = findViewById(R.id.assistOrb)
        wave = findViewById(R.id.assistWave)
        tvText = findViewById(R.id.tvAssistText)
        findViewById<ImageButton>(R.id.btnAssistClose).setOnClickListener { finishQuietly() }

        orb.start()
        wave.start()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            beginListening()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /** 沉浸式全屏：隐藏状态栏与导航栏，边缘特效铺满屏幕。 */
    private fun applyFullScreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun beginListening() {
        if (isFinishing) return
        val e = SpeechEngines.create(this, vadEosMillis = VAD_EOS)
        if (!e.isAvailable(this)) {
            toast(getString(R.string.warn_mic_unavailable))
            finishQuietly()
            return
        }
        engine = e
        startedAt = SystemClock.elapsedRealtime()
        lastSpeechAt = startedAt
        tvText.text = getString(R.string.assist_listening)
        e.start(this, listener)
        handler.postDelayed(watchdog, WATCHDOG_TICK)
    }

    private val listener = object : SpeechEngine.Listener {
        override fun onPartial(text: String) = runOnUiThread {
            if (text.isNotBlank()) {
                tvText.text = text
                gotSpeech = true
                lastSpeechAt = SystemClock.elapsedRealtime()
                // 引擎未回吐音量，用结果到达做一次脉冲，让特效跟随说话节奏。
                val lvl = 0.62f + 0.34f * ((SystemClock.elapsedRealtime() % 320L) / 320f)
                orb.setLevel(lvl)
                wave.setLevel(lvl)
            }
        }

        override fun onResult(text: String) = runOnUiThread {
            if (text.isNotBlank()) tvText.text = text
            submit()
        }

        override fun onError(message: String) {
            runOnUiThread { if (gotSpeech) submit() else finishQuietly() }
        }

        override fun onStateChanged(listening: Boolean) = runOnUiThread {
            // 引擎结束时不再在此关闭：最终结果/错误回调会接管提交或退出。
        }
    }

    /** 提交识别文本并交给主界面执行。 */
    private fun submit() {
        if (submitted) return
        submitted = true
        handler.removeCallbacks(watchdog)
        val text = tvText.text?.toString()?.trim().orEmpty()
        runCatching { engine?.stop() }
        runCatching { engine?.release() }
        engine = null
        val placeholder = getString(R.string.assist_listening)
        if (text.isEmpty() || text == placeholder) {
            finishQuietly()
            return
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_AUTO_TASK, text)
        }
        startActivity(intent)
        finish()
    }

    /** 取消识别并安静退出，不提交任何内容。 */
    private fun finishQuietly() {
        if (isFinishing) return
        submitted = true
        handler.removeCallbacks(watchdog)
        runCatching { engine?.cancel() }
        runCatching { engine?.release() }
        engine = null
        finish()
    }

    private fun toast(message: String) {
        if (message.isBlank()) return
        runCatching { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroy() {
        submitted = true
        handler.removeCallbacks(watchdog)
        runCatching { orb.stop() }
        runCatching { wave.stop() }
        runCatching { engine?.release() }
        engine = null
        super.onDestroy()
    }

    private companion object {
        /** 说完后的静音判定阈值（毫秒）：约 1.5 秒的停顿即认定说完了。 */
        const val VAD_EOS = 1500
        /** 看门狗兜底：已有语音后多久无新内容即提交。 */
        const val SILENCE_AFTER_SPEECH = 1700L
        /** 全程未检测到语音时的退出时间。 */
        const val SILENCE_NO_SPEECH = 6000L
        /** 单次听写最长时长。 */
        const val MAX_LISTEN = 60_000L
        const val WATCHDOG_TICK = 150L
    }
}
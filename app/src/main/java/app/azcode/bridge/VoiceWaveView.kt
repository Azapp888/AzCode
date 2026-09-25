package app.azcode.bridge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.sin

/**
 * 语音识别波动条。
 *
 * 以一排竖直短棒做正弦起伏，识别期间持续动画；外部可通过 [setLevel] 注入音量让振幅跟随说话强弱。
 * 不依赖具体识别引擎，系统内置与讯飞引擎都能驱动。
 */
class VoiceWaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF5686FE.toInt()
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(3f)
    }

    private val barCount = 28
    private var running = false
    private var phase = 0f

    @Volatile private var level = 0.35f

    private val ticker = object : Runnable {
        override fun run() {
            phase += 0.18f
            level = (level * 0.93f).coerceAtLeast(0.18f)
            invalidate()
            if (running) postOnAnimation(this)
        }
    }

    /** 注入 0.1~1 的音量强度。 */
    fun setLevel(value: Float) {
        level = value.coerceIn(0.1f, 1f)
    }

    fun start() {
        if (running) return
        running = true
        phase = 0f
        postOnAnimation(ticker)
    }

    fun stop() {
        running = false
        removeCallbacks(ticker)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (height == 0 || width == 0) return
        val cy = height / 2f
        val gap = width.toFloat() / barCount
        val base = if (running) 0.22f else 0.08f
        for (i in 0 until barCount) {
            val x = gap * (i + 0.5f)
            val wave = sin(phase + i * 0.55f)
            val h = (base + abs(wave) * level) * height * 0.9f
            val half = (h / 2f).coerceAtLeast(dp(1.5f))
            canvas.drawLine(x, cy - half, x, cy + half, paint)
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
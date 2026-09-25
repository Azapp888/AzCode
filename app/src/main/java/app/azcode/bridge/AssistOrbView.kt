package app.azcode.bridge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.min
import kotlin.math.sin

/**
 * 全屏助理特效背景。
 *
 * 绘制三层视觉：
 *  - 中心随时间呼吸的蓝色光晕（"聆听核心"）
 *  - 由中心向外扩散、渐隐的同心圆环
 *  - 贴屏幕四边的呼吸式青色描边光
 *
 * 外部可用 [setLevel] 注入说话强度，让核心亮度跟随音量变化。
 * 通过 [start]/[stop] 控制动画，不依赖识别引擎，可独立驱动或与 VoiceWaveView 并用。
 */
class AssistOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private var phase = 0f
    private var running = false

    @Volatile
    private var level = 0.4f

    private val ticker = object : Runnable {
        override fun run() {
            phase += 0.016f
            if (phase > 1f) phase -= 1f
            level = (level * 0.95f).coerceAtLeast(0.28f)
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
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        val cx = w / 2f
        val cy = h * 0.40f
        val base = min(w, h) * 0.16f
        val breath = 1f + 0.06f * sin(phase * TWO_PI * 2f)
        val coreRadius = (base * (1.6f + 1.2f * level) * breath).coerceAtLeast(dp(24f))

        // 1) 中心核心光晕（柔和，避免遮挡下面页面内容）
        corePaint.shader = RadialGradient(
            cx, cy, coreRadius,
            intArrayOf(0x663D6BFF, 0x261E3FA8, 0x00000000),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, coreRadius, corePaint)

        // 2) 向外扩散的同心圆环
        ringPaint.strokeWidth = dp(2f)
        for (i in 0 until 3) {
            val t = (phase + i / 3f) % 1f
            val alpha = ((1f - t) * 78f).toInt().coerceIn(0, 255)
            ringPaint.color = Color.argb(alpha, 0x6E, 0x9B, 0xFF)
            canvas.drawCircle(cx, cy, base * (0.7f + t * 1.7f), ringPaint)
        }

        // 3) 贴边呼吸光
        val margin = dp(3f)
        val rect = RectF(margin, margin, w - margin, h - margin)
        val radius = dp(28f)
        val glow = (0.35f + 0.35f * sin(phase * TWO_PI)).coerceIn(0f, 1f)
        edgePaint.strokeWidth = dp(7f)
        edgePaint.shader = null
        edgePaint.color = Color.argb((glow * 80f).toInt(), 0x39, 0x64, 0xFE)
        canvas.drawRoundRect(rect, radius, radius, edgePaint)
        edgePaint.strokeWidth = dp(2f)
        edgePaint.color = Color.argb((glow * 210f).toInt(), 0xA9, 0xC3, 0xFF)
        canvas.drawRoundRect(rect, radius, radius, edgePaint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private companion object {
        const val TWO_PI = 6.2831855f
    }
}
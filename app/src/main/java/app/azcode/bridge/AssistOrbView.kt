package app.azcode.bridge

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.sin

/**
 * 智能助理的全屏「物理边缘」呼吸蓝光。
 *
 * 只绘制贴合屏幕物理边框的蓝色呼吸描边（外圈模糊光晕 + 内圈实线），与 AI 操作手机时的
 * [OperationGlowView] 同构：描边中心落在窗口边缘、圆角贴住屏幕自身圆角，因此看起来像屏幕
 * 硬件边缘在发光，而不是悬浮在画面中间的动画。中间区域完全透明，不遮挡下方页面。
 *
 * 通过 [start]/[stop] 控制动画；[setLevel] 可注入说话强度，让边缘亮度随音量轻微起伏。
 */
class AssistOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density

    /** 与 AI 操作光效一致的蓝色渐变（primary #3964FE → #5686FE → #A9C3FF → 回到 primary）。 */
    private val edgeColors = intArrayOf(
        Color.parseColor("#3964FE"),
        Color.parseColor("#5686FE"),
        Color.parseColor("#A9C3FF"),
        Color.parseColor("#5686FE"),
        Color.parseColor("#3964FE"),
    )

    /** 外圈光晕：带模糊的粗描边，营造整屏贴边的柔光。 */
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 14f * density
        maskFilter = BlurMaskFilter(12f * density, BlurMaskFilter.Blur.NORMAL)
    }

    /** 内圈实线：让光效边界更清晰。 */
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * density
        maskFilter = BlurMaskFilter(1.5f * density, BlurMaskFilter.Blur.NORMAL)
    }

    private var edgeShader: Shader? = null

    private val bounds = RectF()

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var progress = 0f

    /** 说话强度 0.1~1，越大边缘越亮。 */
    @Volatile
    private var level = 0.5f

    /**
     * 呼吸动画限制在约 15fps：光晕变化本身很慢，低帧率肉眼无差异，但能显著降低这个
     * 全屏软件层视图的功耗（与 [OperationGlowView] 保持一致）。
     */
    private val frameRunnable = object : Runnable {
        override fun run() {
            val phase = (System.currentTimeMillis() % BREATH_PERIOD_MS).toFloat() / BREATH_PERIOD_MS
            progress = ((sin(phase * 2 * PI - PI / 2) + 1.0) / 2.0).toFloat()
            // 说话强度缓慢回落，避免语音间隙边缘忽然变暗。
            level = (level * 0.94f).coerceAtLeast(0.35f)
            invalidate()
            if (running) handler.postDelayed(this, FRAME_MS)
        }
    }

    init {
        // 模糊光晕需要软件层渲染。
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** 注入 0.1~1 的音量强度。 */
    fun setLevel(value: Float) {
        level = value.coerceIn(0.1f, 1f)
    }

    fun start() {
        if (running) return
        running = true
        handler.post(frameRunnable)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(frameRunnable)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        // 以屏幕中心为原点做扫掠渐变，让蓝光沿四周边缘连续过渡（首尾同色，接缝不可见）。
        edgeShader = SweepGradient(
            w / 2f,
            h / 2f,
            edgeColors,
            floatArrayOf(0f, 0.28f, 0.5f, 0.72f, 1f),
        )
        glowPaint.shader = edgeShader
        corePaint.shader = edgeShader
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        // 描边中心落在窗口边缘（inset=0）：一半落在屏幕外被裁掉，光效从物理边框处起亮。
        bounds.set(0f, 0f, w, h)
        val radius = 16f * density
        val boost = 0.72f + 0.5f * level
        val alpha = ((110 + 145 * progress) * boost).toInt().coerceIn(0, 255)
        glowPaint.alpha = alpha
        corePaint.alpha = ((alpha * 0.75f).toInt() + 30).coerceIn(0, 255)

        canvas.drawRoundRect(bounds, radius, radius, glowPaint)
        canvas.drawRoundRect(bounds, radius, radius, corePaint)
    }

    private companion object {
        const val BREATH_PERIOD_MS = 2600L
        const val FRAME_MS = 66L
    }
}

package app.azcode.bridge

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.View
import kotlin.math.PI
import kotlin.math.sin

/**
 * AI 状态光效：覆盖整个物理屏幕的呼吸式描边光晕。
 *
 * 只要无障碍服务处于开启状态，该光效就常驻呼吸显示，作为「AzCode 正在守护设备」的视觉标识；
 * AI 真正执行无障碍操作时（[setOperating]）光晕增强并在左下角显示「AI 正在操作手机」，操作结束后
 * 恢复为常态呼吸但不会消失。
 *
 * 该视图由无障碍服务以 `TYPE_ACCESSIBILITY_OVERLAY` 窗口添加，因此无需额外权限，也不拦截触摸
 * （窗口带 `FLAG_NOT_TOUCHABLE`）。
 */
class OperationGlowView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density

    private val glowColor = Color.parseColor("#3964FE")

    /** 外圈光晕：带模糊的粗描边。 */
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f * density
        color = glowColor
        maskFilter = BlurMaskFilter(7f * density, BlurMaskFilter.Blur.NORMAL)
    }

    /** 内圈实线：让光效边界更清晰。 */
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        color = Color.parseColor("#7FA0FF")
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12f * context.resources.displayMetrics.scaledDensity
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC061C3F")
    }

    private val bounds = RectF()

    /** 0..1 的呼吸相位。 */
    private var progress = 0f

    /** 是否正在进行无障碍操作；决定光晕强度与左下角提示文案。 */
    @Volatile
    private var operating = false

    private val handler = Handler(Looper.getMainLooper())

    private var running = false

    /**
     * 呼吸动画刻意限制在约 15fps：光晕变化本身很慢，低帧率肉眼无差异，
     * 但能显著降低这个常驻全屏软件层视图的功耗。
     */
    private val frameRunnable = object : Runnable {
        override fun run() {
            val phase = (System.currentTimeMillis() % BREATH_PERIOD_MS).toFloat() / BREATH_PERIOD_MS
            // 正弦让呼吸两端更平滑，避免线性动画在极值处的顿挫感。
            progress = ((sin(phase * 2 * PI - PI / 2) + 1.0) / 2.0).toFloat()
            invalidate()
            if (running) handler.postDelayed(this, FRAME_MS)
        }
    }

    init {
        // 模糊光晕需要软件层渲染。
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isClickable = false
        isFocusable = false
        // 不进入无障碍节点树，避免影响 Agent 自身的读屏结果。
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** 开始常驻呼吸动画。 */
    fun start() {
        if (running) return
        running = true
        handler.post(frameRunnable)
    }

    /** 停止动画，避免窗口移除后仍在空转。 */
    fun stop() {
        running = false
        handler.removeCallbacks(frameRunnable)
    }

    /** 切换「正在操作」状态：仅影响光晕强弱与左下角提示，光效本身始终保留。 */
    fun setOperating(value: Boolean) {
        if (operating == value) return
        operating = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 光效紧贴物理屏幕边缘，覆盖到状态栏与导航栏区域。
        val inset = glowPaint.strokeWidth / 2f
        bounds.set(inset, inset, width - inset, height - inset)
        val radius = 28f * density

        // 常态呼吸偏柔和，操作时增强亮度以形成明显区分。
        val base = if (operating) 110 else 60
        val span = if (operating) 145 else 85
        val alpha = (base + span * progress).toInt().coerceIn(0, 255)
        glowPaint.alpha = alpha
        corePaint.alpha = ((alpha * 0.75f).toInt() + 30).coerceIn(0, 255)

        canvas.drawRoundRect(bounds, radius, radius, glowPaint)
        canvas.drawRoundRect(bounds, radius, radius, corePaint)

        drawLabel(canvas)
    }

    private fun drawLabel(canvas: Canvas) {
        val text = context.getString(
            if (operating) R.string.ai_operating_hint else R.string.ai_ready_hint,
        )
        val padH = 12f * density
        val padV = 7f * density
        val margin = 20f * density
        // 底部留出导航栏空间，避免提示被手势条或三键导航遮挡。
        val bottomMargin = 72f * density

        val textWidth = labelPaint.measureText(text)
        val fm = labelPaint.fontMetrics
        val textHeight = fm.descent - fm.ascent

        val left = margin
        val top = height - bottomMargin - (textHeight + padV * 2f)
        val right = left + textWidth + padH * 2f
        val bottom = top + textHeight + padV * 2f

        val pill = RectF(left, top, right, bottom)
        val pillRadius = pill.height() / 2f
        // 常态下提示更淡，避免长期停留在屏幕上造成干扰。
        labelBgPaint.alpha = if (operating) 0xCC else 0x99
        canvas.drawRoundRect(pill, pillRadius, pillRadius, labelBgPaint)

        val baseline = pill.centerY() - (fm.ascent + fm.descent) / 2f
        labelPaint.alpha = if (operating) 0xFF else 0xCC
        canvas.drawText(text, left + padH, baseline, labelPaint)
        labelPaint.alpha = 0xFF
    }

    private companion object {
        /** 一次完整呼吸的周期。 */
        const val BREATH_PERIOD_MS = 2600L

        /** 动画帧间隔，约 15fps。 */
        const val FRAME_MS = 66L
    }
}

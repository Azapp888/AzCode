package app.azcode.bridge

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * AI 操作提示光效：覆盖整个屏幕的呼吸式描边光晕，并在左下角显示「AI 正在操作手机」。
 *
 * 该视图由无障碍服务以 `TYPE_ACCESSIBILITY_OVERLAY` 窗口添加，因此无需额外权限，也不拦截触摸
 * （窗口带 `FLAG_NOT_TOUCHABLE`）。Agent 每次执行无障碍操作时触发一次脉冲，随后自动淡出。
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

    private var progress = 0f

    private var animator: ValueAnimator? = null

    init {
        // 模糊光晕需要软件层渲染。
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isClickable = false
        isFocusable = false
        // 不进入无障碍节点树，避免影响 Agent 自身的读屏结果。
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** 开始呼吸动画。 */
    fun start() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** 停止动画，避免窗口移除后仍在空转。 */
    fun stop() {
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val inset = 8f * density + glowPaint.strokeWidth / 2f
        bounds.set(inset, inset, width - inset, height - inset)
        val radius = 28f * density

        val alpha = (110 + 145 * progress).toInt().coerceIn(0, 255)
        glowPaint.alpha = alpha
        corePaint.alpha = (90 + 120 * progress).toInt().coerceIn(0, 255)

        canvas.drawRoundRect(bounds, radius, radius, glowPaint)
        canvas.drawRoundRect(bounds, radius, radius, corePaint)

        drawLabel(canvas)
    }

    private fun drawLabel(canvas: Canvas) {
        val text = context.getString(R.string.ai_operating_hint)
        val padH = 12f * density
        val padV = 7f * density
        val margin = 20f * density
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
        canvas.drawRoundRect(pill, pillRadius, pillRadius, labelBgPaint)

        val baseline = pill.centerY() - (fm.ascent + fm.descent) / 2f
        canvas.drawText(text, left + padH, baseline, labelPaint)
    }
}

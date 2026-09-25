package app.azcode.bridge

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * AI 状态光效：全屏呼吸描边 + 左下角悬浮气泡。
 *
 * 本次任务内一旦触发过无障碍操作（[setOperating]）即显示全屏呼吸光效，并常亮到任务结束；
 * 左下角悬浮气泡先显示「AI 正在操作手机」，随后展示 AI 的文字输出（调用方已剔除代码行），
 * 由调用方在最多 5s 后自动关闭。
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

    private val bubbleTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * context.resources.displayMetrics.scaledDensity
    }

    private val bubbleBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E6061C3F")
    }

    private val bounds = RectF()

    /** 气泡文本布局缓存：同一段文字/宽度下复用，避免 15fps 重绘时反复构建。 */
    private var cachedLayout: StaticLayout? = null
    private var cachedText: String? = null
    private var cachedLayoutWidth = 0

    /** 0..1 的呼吸相位。 */
    private var progress = 0f

    /** 是否正在进行无障碍操作；决定是否绘制光效与气泡提示文案。 */
    @Volatile
    private var operating = false

    /** AI 的文字输出（已剔除代码行）；可为空。 */
    @Volatile
    private var bubbleText: String? = null

    private val handler = Handler(Looper.getMainLooper())

    private var running = false

    /**
     * 呼吸动画刻意限制在约 15fps：光晕变化本身很慢，低帧率肉眼无差异，
     * 但能显著降低这个全屏软件层视图的功耗。
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

    /** 开始呼吸动画。 */
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

    /** 切换「正在操作」状态：控制全屏光效绘制与呼吸动画。气泡文字由调用方通过 [setBubbleText] 控制。 */
    fun setOperating(value: Boolean) {
        if (operating == value) return
        operating = value
        // 只在需要绘制光效时跑呼吸动画；仅显示气泡时保持静止，避免无谓重绘。
        if (value) start() else stop()
        invalidate()
    }

    /** 设置左下角气泡展示的文字（AI 文字输出或操作提示）。传 null 表示关闭气泡。 */
    fun setBubbleText(value: String?) {
        val next = value?.trim().orEmpty().ifBlank { null }
        if (bubbleText == next) return
        bubbleText = next
        invalidate()
    }

    /** 光效或气泡是否还有需要展示的内容；都没有时调用方会移除窗口。 */
    fun hasContent(): Boolean = operating || !bubbleText.isNullOrBlank()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 仅在 AI 操作手机时绘制全屏光效，操作间隙不显示光晕。
        if (operating) {
            // 光效紧贴物理屏幕边缘，覆盖到状态栏与导航栏区域。
            val inset = glowPaint.strokeWidth / 2f
            bounds.set(inset, inset, width - inset, height - inset)
            val radius = 28f * density

            val alpha = (110 + 145 * progress).toInt().coerceIn(0, 255)
            glowPaint.alpha = alpha
            corePaint.alpha = ((alpha * 0.75f).toInt() + 30).coerceIn(0, 255)

            canvas.drawRoundRect(bounds, radius, radius, glowPaint)
            canvas.drawRoundRect(bounds, radius, radius, corePaint)
        }

        drawBubble(canvas)
    }

    private fun drawBubble(canvas: Canvas) {
        val text = bubbleText ?: return

        val padH = 14f * density
        val padV = 10f * density
        val margin = 20f * density
        // 底部留出导航栏空间，避免气泡被手势条或三键导航遮挡。
        val bottomMargin = 72f * density

        val maxWidth = min(width - 2f * margin, 360f * density).toInt().coerceAtLeast(120)
        val textWidth = (maxWidth - 2f * padH).toInt().coerceAtLeast(1)
        val layout = cachedLayout
            ?.takeIf { cachedText == text && cachedLayoutWidth == textWidth }
            ?: StaticLayout.Builder
                .obtain(text, 0, text.length, bubbleTextPaint, textWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setMaxLines(MAX_BUBBLE_LINES)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setIncludePad(false)
                .build()
                .also {
                    cachedLayout = it
                    cachedText = text
                    cachedLayoutWidth = textWidth
                }

        val bubbleHeight = layout.height + padV * 2f
        val left = margin
        val top = height - bottomMargin - bubbleHeight
        val pill = RectF(left, top, left + textWidth + padH * 2f, top + bubbleHeight)

        bubbleBgPaint.alpha = if (operating) 0xE6 else 0xCC
        canvas.drawRoundRect(pill, 16f * density, 16f * density, bubbleBgPaint)

        canvas.save()
        canvas.translate(left + padH, top + padV)
        layout.draw(canvas)
        canvas.restore()
    }

    private companion object {
        /** 一次完整呼吸的周期。 */
        const val BREATH_PERIOD_MS = 2600L

        /** 动画帧间隔，约 15fps。 */
        const val FRAME_MS = 66L

        /** 气泡最多显示的行数，避免长时间提示遮挡过多屏幕。 */
        const val MAX_BUBBLE_LINES = 4
    }
}

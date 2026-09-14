package app.azcode.bridge

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 思考深度滑块。
 *
 * - 连续拖动，不做任何档位吸附：滑块停在手指位置。
 * - 每帧把「最接近的档位」回报给上层，由上层展示对应深度。
 * - 拉到最高档时，进度条颜色内部浮起粒子/泡泡特效。
 */
class DepthSliderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** 档位数量（关闭 / 快速 / 标准 / 深度 = 4）。 */
    var depthCount: Int = 4
        set(value) {
            field = value.coerceAtLeast(2)
            invalidate()
        }

    private var progressValue: Float = 0f

    /** 当前进度 0..1（写入会触发回调）。 */
    var progress: Float
        get() = progressValue
        set(value) = applyProgress(value, fromUser = false, notify = true)

    /** 最接近的档位下标。 */
    val nearestIndex: Int
        get() = (progressValue * (depthCount - 1)).roundToInt().coerceIn(0, depthCount - 1)

    /** 是否位于最高档。 */
    val isMaxed: Boolean get() = nearestIndex == depthCount - 1

    /** 进度变化回调：progress、最近档位、是否来自用户拖动。 */
    var onProgressChanged: ((progress: Float, nearestIndex: Int, fromUser: Boolean) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val trackRect = RectF()
    private val fillRect = RectF()

    private val colorPrimary = ContextCompat.getColor(context, R.color.primary)
    private val colorTrack = ContextCompat.getColor(context, R.color.surface_muted)
    private val colorThumb = ContextCompat.getColor(context, R.color.surface)

    // 轨道加粗到与滑块圆球直径相当，视觉上更厚重、便于拖动。
    private val thumbRadius = dp(16f)
    private val trackHeight = thumbRadius * 2f
    private val ringWidth = dp(3f)

    private class Bubble(var x: Float, var y: Float, var r: Float, var speed: Float, var alpha: Int)

    private val bubbles = ArrayList<Bubble>(12)
    private var lastFrame = 0L
    private var animator: ValueAnimator? = null

    init {
        isClickable = true
        isFocusable = true
    }

    /**
     * 自定义 View 的默认 onMeasure 在 AT_MOST（wrap_content）下会直接返回父级允许的
     * 全部高度，导致滑块被拉伸、把整个模型/思考深度面板撑满屏幕。这里显式给出固有高度：
     * 圆球直径 + 上下各 8dp 余量。
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (thumbRadius * 2f + dp(16f)).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec),
        )
    }

    /** 静默设置进度：不回调、不触发费用确认，但会同步特效状态。 */
    fun setProgressSilently(value: Float) = applyProgress(value, fromUser = false, notify = false)

    /** 用户拖动时吸附到最近档位，调节幅度粗、不落在档位之间。 */
    private fun snap(value: Float): Float {
        val last = (depthCount - 1).coerceAtLeast(1)
        return ((value.coerceIn(0f, 1f) * last).roundToInt()).toFloat() / last
    }

    private fun applyProgress(value: Float, fromUser: Boolean, notify: Boolean) {
        val target = if (fromUser) snap(value) else value
        val p = target.coerceIn(0f, 1f)
        val changed = abs(p - progressValue) > 0.0001f
        val wasMax = isMaxed
        progressValue = p
        val nowMax = isMaxed
        if (nowMax != wasMax) {
            if (nowMax) startBubbles() else stopBubbles()
        }
        if (notify && (changed || fromUser)) {
            onProgressChanged?.invoke(progressValue, nearestIndex, fromUser)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        val left = paddingLeft + thumbRadius
        val right = width - paddingRight - thumbRadius
        if (right <= left) return

        val top = cy - trackHeight / 2f
        val bottom = cy + trackHeight / 2f
        val radius = trackHeight / 2f

        trackPaint.color = colorTrack
        trackRect.set(left, top, right, bottom)
        canvas.drawRoundRect(trackRect, radius, radius, trackPaint)

        val fillWidth = (right - left) * progressValue
        fillRect.set(left, top, left + fillWidth, bottom)
        fillPaint.color = colorPrimary
        canvas.drawRoundRect(fillRect, radius, radius, fillPaint)

        if (isMaxed && bubbles.isNotEmpty() && fillWidth > 0f) {
            canvas.save()
            canvas.clipRect(fillRect)
            bubbles.forEach { b ->
                bubblePaint.color = Color.argb(b.alpha, 255, 255, 255)
                canvas.drawCircle(left + b.x * fillWidth, top + b.y * (bottom - top), b.r, bubblePaint)
            }
            canvas.restore()
        }

        val cx = left + (right - left) * progressValue
        thumbPaint.color = colorThumb
        canvas.drawCircle(cx, cy, thumbRadius, thumbPaint)
        thumbRingPaint.color = colorPrimary
        thumbRingPaint.strokeWidth = ringWidth
        canvas.drawCircle(cx, cy, thumbRadius - ringWidth / 2f, thumbRingPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                updateFromTouch(event.x)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                updateFromTouch(event.x)
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateFromTouch(x: Float) {
        val left = paddingLeft + thumbRadius
        val right = width - paddingRight - thumbRadius
        if (right <= left) return
        applyProgress((x - left) / (right - left), fromUser = true, notify = true)
    }

    private fun startBubbles() {
        if (animator != null) return
        bubbles.clear()
        repeat(10) { bubbles.add(newBubble(initial = true)) }
        lastFrame = SystemClock.uptimeMillis()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { stepBubbles() }
            start()
        }
    }

    private fun stopBubbles() {
        animator?.cancel()
        animator = null
        bubbles.clear()
        invalidate()
    }

    private fun newBubble(initial: Boolean) = Bubble(
        x = Random.nextFloat(),
        y = if (initial) Random.nextFloat() else 1.05f,
        r = dp(Random.nextFloat() * 1.6f + 1.2f),
        speed = Random.nextFloat() * 0.24f + 0.18f,
        alpha = Random.nextInt(70, 150),
    )

    private fun stepBubbles() {
        val now = SystemClock.uptimeMillis()
        val dt = ((now - lastFrame) / 1000f).coerceIn(0f, 0.05f)
        lastFrame = now
        bubbles.forEach { b ->
            b.y -= b.speed * dt
            if (b.y < -0.05f) {
                b.x = Random.nextFloat()
                b.y = 1.05f
                b.r = dp(Random.nextFloat() * 1.6f + 1.2f)
                b.speed = Random.nextFloat() * 0.24f + 0.18f
                b.alpha = Random.nextInt(70, 150)
            }
        }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        stopBubbles()
        super.onDetachedFromWindow()
    }
}

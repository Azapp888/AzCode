package app.azcode.bridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * 无障碍服务（移植自 DSH Mobile，MIT）：
 *  - 读屏 dumpScreenJson()：遍历可见节点，输出文本+坐标+可点击性
 *  - 按文本点击 tapText() / 坐标点击 tap()
 *  - 滑动 swipe()
 *  - 全局动作 back/home/recents
 *
 * 需用户在系统设置手动开启，未开启时 instance 为 null，所有能力失效。
 */
class AzAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        uiHandler.removeCallbacks(hideRunnable)
        detachGlow()
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        // 少数机型停用无障碍时只走 unbind 不走 destroy，这里一并清理，避免光效残留在屏幕上。
        uiHandler.removeCallbacks(hideRunnable)
        detachGlow()
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    fun dumpScreenJson(): String {
        val arr = JSONArray()
        var count = 0
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || count >= MAX_NODES) return
            val rect = Rect().also { node.getBoundsInScreen(it) }
            val visible = rect.width() > 0 && rect.height() > 0 &&
                rect.top < rootHeight && rect.bottom > 0
            if (visible) {
                val text = node.text?.toString()?.trim().orEmpty()
                val desc = node.contentDescription?.toString()?.trim().orEmpty()
                if (text.isNotEmpty() || desc.isNotEmpty() || node.isClickable) {
                    arr.put(JSONObject().apply {
                        put("text", text)
                        put("desc", desc)
                        put("cls", node.className?.toString() ?: "")
                        put("x", rect.centerX())
                        put("y", rect.centerY())
                        put("w", rect.width())
                        put("h", rect.height())
                        put("clickable", node.isClickable)
                    })
                    count++
                }
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(rootInActiveWindow)
        return JSONObject().put("ok", true).put("nodes", arr).toString()
    }

    private val rootHeight: Int
        get() = resources.displayMetrics.heightPixels

    fun tapText(text: String): Boolean {
        val target = findNodeByText(text) ?: return false
        val rect = Rect().also { target.getBoundsInScreen(it) }
        return dispatchTap(rect.exactCenterX(), rect.exactCenterY())
    }

    private fun findNodeByText(text: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        root.findAccessibilityNodeInfosByText(text).firstOrNull { it.isClickable }?.let { return it }
        var hit: AccessibilityNodeInfo? = null
        fun walk(node: AccessibilityNodeInfo?) {
            if (hit != null || node == null) return
            val t = node.text?.toString().orEmpty()
            val d = node.contentDescription?.toString().orEmpty()
            if ((t.contains(text) || d.contains(text)) && (node.isClickable || node.parent != null)) {
                var cur: AccessibilityNodeInfo? = node
                while (cur != null) {
                    if (cur.isClickable) { hit = cur; return }
                    cur = cur.parent
                }
                if (hit == null) hit = node
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root)
        return hit
    }

    internal fun dispatchTap(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    internal fun dispatchSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(50, 2000)))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun globalAction(name: String): Boolean {
        val action = when (name.lowercase()) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            else -> return false
        }
        return performGlobalAction(action)
    }

    /**
     * 向当前聚焦的输入框写入文本（Unicode 安全，无需切换输入法）。
     * [append] 为 true 时追加到已有内容之后，否则整体替换。
     */
    fun setFocusedText(text: String, append: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        if (!node.isEditable || !node.isEnabled) return false
        val merged = if (append) node.text?.toString().orEmpty() + text else text
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, merged)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    // ==================== AI 操作光效 ====================

    private val uiHandler = Handler(Looper.getMainLooper())

    /** 操作提示到期后收起光效（若此时没有待展示的 AI 输出则一并移除窗口）。 */
    private val hideRunnable = Runnable {
        glowView?.setOperating(false)
        detachGlowIfEmpty()
    }

    private var glowView: OperationGlowView? = null

    /**
     * 标记一次无障碍操作（AI 检测到需要操作用户手机时调用）：
     * 显示全屏呼吸光效，并在左下角悬浮气泡显示「AI 正在操作手机」；
     * 操作暂停超过 [OPERATING_TIMEOUT_MS] 后光效淡出，但已产出的 AI 文字输出仍保留在气泡中。
     * 可在任意线程调用，内部会切到主线程处理窗口操作。
     */
    fun showOperating() {
        uiHandler.post {
            val view = ensureGlow() ?: return@post
            view.setOperating(true)
            uiHandler.removeCallbacks(hideRunnable)
            uiHandler.postDelayed(hideRunnable, OPERATING_TIMEOUT_MS)
        }
    }

    /** 结束「正在操作」状态，收起光效但保留气泡中的 AI 文字输出。 */
    fun hideOperating() {
        uiHandler.post {
            uiHandler.removeCallbacks(hideRunnable)
            glowView?.setOperating(false)
            detachGlowIfEmpty()
        }
    }

    /**
     * 更新左下角悬浮气泡中的 AI 输出文字（已剔除代码行）。传 null/空串表示清除该段输出。
     * 无障碍服务未开启时不显示。
     */
    fun showBubbleText(text: String?) {
        uiHandler.post {
            val view = ensureGlow() ?: return@post
            view.setBubbleText(text)
            detachGlowIfEmpty()
        }
    }

    /** 任务结束/取消时彻底清除光效与气泡。 */
    fun clearOverlays() {
        uiHandler.post {
            uiHandler.removeCallbacks(hideRunnable)
            detachGlow()
        }
    }

    private fun ensureGlow(): OperationGlowView? {
        glowView?.let { return it }
        val wm = getSystemService(WindowManager::class.java) ?: return null
        val view = OperationGlowView(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 覆盖整个物理屏幕：延伸到刘海/挖孔区域。
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            // Android 11+ 明确不消费任何系统栏 inset，保证窗口铺满整屏。
            if (Build.VERSION.SDK_INT >= 30) {
                fitInsetsTypes = 0
            }
        }
        return runCatching { wm.addView(view, lp); view }.getOrNull()?.also { glowView = it }
    }

    /** 光效与气泡都无内容时移除窗口，避免常驻一个全屏软件层视图。 */
    private fun detachGlowIfEmpty() {
        if (glowView?.hasContent() == false) detachGlow()
    }

    private fun detachGlow() {
        val view = glowView ?: return
        glowView = null
        view.stop()
        runCatching { getSystemService(WindowManager::class.java)?.removeView(view) }
    }

    companion object {
        @Volatile
        internal var instance: AzAccessibilityService? = null

        private const val MAX_NODES = 200

        /**
         * 一次操作结束后，全屏光效的保留时长：连续操作会不断续期，
         * 停止操作后光效收起；若气泡中还有 AI 文字输出则窗口继续保留。
         */
        private const val OPERATING_TIMEOUT_MS = 2500L

        fun isEnabled(): Boolean = instance != null

        fun canPerformGestures(): Boolean = instance != null && Build.VERSION.SDK_INT >= 24

        fun tap(x: Float, y: Float): Boolean = instance?.dispatchTap(x, y) ?: false

        fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean =
            instance?.dispatchSwipe(x1, y1, x2, y2, durationMs) ?: false

        fun setFocusedText(text: String, append: Boolean = false): Boolean =
            instance?.setFocusedText(text, append) ?: false

        /** 触发一次 AI 操作提示；无障碍服务未开启时不显示。 */
        fun pulseOperating() {
            instance?.showOperating()
        }

        /** 更新左下角悬浮气泡中的 AI 输出文字（已剔除代码行）。 */
        fun setBubble(text: String?) {
            instance?.showBubbleText(text)
        }

        /** 结束「正在操作」提示，收起光效但保留气泡文字。 */
        fun stopOperating() {
            instance?.hideOperating()
        }

        /** 任务结束/取消时清除光效与气泡。 */
        fun clearOverlays() {
            instance?.clearOverlays()
        }
    }
}

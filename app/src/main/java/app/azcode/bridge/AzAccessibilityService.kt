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
        uiHandler.removeCallbacks(bubbleHideRunnable)
        detachGlow()
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        // 少数机型停用无障碍时只走 unbind 不走 destroy，这里一并清理，避免光效残留在屏幕上。
        uiHandler.removeCallbacks(bubbleHideRunnable)
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

    /** 气泡最多显示 [BUBBLE_TIMEOUT_MS]，到期自动关闭（呼吸光效仍持续到任务结束）。 */
    private val bubbleHideRunnable = Runnable {
        pendingBubbleText = null
        glowView?.setBubbleText(null)
        detachGlowIfEmpty()
    }

    private var glowView: OperationGlowView? = null

    /**
     * 期望展示的气泡文字。真正的显隐还要看应用是否在前台（[renderBubble]）：
     * 主界面在前台时聊天区已能看到 AI 输出，气泡保持隐藏；退出应用到其他界面后才显示。
     */
    private var pendingBubbleText: String? = null

    /**
     * 标记一次无障碍操作（AI 检测到需要操作用户手机时调用）：
     * 显示全屏呼吸光效，并在左下角悬浮气泡显示「AI 正在操作手机」（应用自身界面在前台时气泡隐藏）。
     *
     * 呼吸光效只要在本次任务中触发过一次，就持续显示到任务结束（[clearOverlays]），
     * 期间不会自动收起；气泡则最多显示 [BUBBLE_TIMEOUT_MS] 后自动关闭。
     * 可在任意线程调用，内部会切到主线程处理窗口操作。
     */
    fun showOperating() {
        uiHandler.post {
            ensureGlow() ?: return@post
            // 保持常亮：不安排自动收起，交由任务结束时的 clearOverlays() 统一关闭。
            glowView?.setOperating(true)
            showBubble(getString(R.string.ai_operating_hint))
        }
    }

    /** 结束「正在操作」状态并收起光效（仅在需要强制关闭时使用，任务结束走 [clearOverlays]）。 */
    fun hideOperating() {
        uiHandler.post {
            glowView?.setOperating(false)
            detachGlowIfEmpty()
        }
    }

    /**
     * 更新左下角悬浮气泡中的 AI 输出文字（已剔除代码行）。传 null/空串表示立即关闭气泡。
     * 无障碍服务未开启时不显示。应用自身界面在前台时气泡保持隐藏，退出应用后才显示；
     * 每次实际显示都会重置 [BUBBLE_TIMEOUT_MS] 自动关闭计时。
     */
    fun showBubbleText(text: String?) {
        uiHandler.post { showBubble(text) }
    }

    /**
     * 记录期望展示的气泡文字，并按当前前台状态决定是否真正显示。
     * 传 null/空串表示立即关闭气泡。
     */
    private fun showBubble(text: String?) {
        pendingBubbleText = text?.trim().orEmpty().ifBlank { null }
        renderBubble()
    }

    /**
     * 依据「应用是否在前台」渲染左下角悬浮气泡：
     * 应用自身界面在前台时不显示（聊天区已能看到 AI 输出），退出应用后才显示，
     * 并重置 [BUBBLE_TIMEOUT_MS] 自动关闭计时。
     */
    private fun renderBubble() {
        uiHandler.removeCallbacks(bubbleHideRunnable)
        val text = pendingBubbleText
        if (text == null || AzCodeApp.isInForeground) {
            glowView?.setBubbleText(null)
            detachGlowIfEmpty()
            return
        }
        val view = ensureGlow() ?: return
        view.setBubbleText(text)
        uiHandler.postDelayed(bubbleHideRunnable, BUBBLE_TIMEOUT_MS)
    }

    /** 应用前后台切换时调用：前台隐去气泡，回到后台时补显示最近一条待展示文字。 */
    fun onAppForegroundChanged() {
        uiHandler.post { renderBubble() }
    }

    /** 任务结束/取消时彻底清除光效与气泡。 */
    fun clearOverlays() {
        uiHandler.post {
            uiHandler.removeCallbacks(bubbleHideRunnable)
            // 一并清空待展示文字，避免任务结束后应用切到后台又把旧气泡补显示出来。
            pendingBubbleText = null
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
         * 左下角悬浮气泡的最长显示时长，到期自动关闭。
         * 全屏呼吸光效不设超时：本次任务内触发过一次无障碍操作后即常亮到任务结束。
         */
        private const val BUBBLE_TIMEOUT_MS = 5000L

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

        /** 应用前后台切换时调用（见 [AzCodeApp]），用于控制左下角气泡显隐。 */
        fun onAppForegroundChanged() {
            instance?.onAppForegroundChanged()
        }
    }
}

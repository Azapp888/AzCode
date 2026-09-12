package app.azcode.bridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
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
        if (instance === this) instance = null
        super.onDestroy()
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

    companion object {
        @Volatile
        internal var instance: AzAccessibilityService? = null

        private const val MAX_NODES = 200

        fun isEnabled(): Boolean = instance != null

        fun canPerformGestures(): Boolean = instance != null && Build.VERSION.SDK_INT >= 24

        fun tap(x: Float, y: Float): Boolean = instance?.dispatchTap(x, y) ?: false

        fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean =
            instance?.dispatchSwipe(x1, y1, x2, y2, durationMs) ?: false
    }
}

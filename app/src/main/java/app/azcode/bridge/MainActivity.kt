package app.azcode.bridge

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/**
 * 聊天式主界面（DeepSeek 风格）：
 *  - 用户/助手消息以气泡呈现
 *  - 工具调用以可折叠卡片呈现，默认收起，失败时自动展开
 *  - 所有配置与设备能力入口收敛到 SettingsActivity
 */
class MainActivity : Activity() {

    private lateinit var chatContainer: LinearLayout
    private lateinit var svChat: ScrollView
    private lateinit var etTask: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var btnStop: ImageButton
    private lateinit var dotStatus: View
    private lateinit var tvHeaderStatus: TextView

    @Volatile private var runner: AgentRunner? = null
    private var worker: Thread? = null
    @Volatile private var destroyed = false

    private var typingView: View? = null
    private var activeTool: ToolCard? = null
    private var welcomeView: View? = null

    private val colorRunning = Color.parseColor("#6B7280")
    private val colorOk = Color.parseColor("#16A34A")
    private val colorFail = Color.parseColor("#DC2626")

    private val shizukuPermissionListener =
        object : rikka.shizuku.Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                runOnUiThread {
                    val msg = if (grantResult == PackageManager.PERMISSION_GRANTED)
                        "Shizuku 已授权" else "Shizuku 授权被拒绝"
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        chatContainer = findViewById(R.id.chatContainer)
        svChat = findViewById(R.id.svChat)
        etTask = findViewById(R.id.etTask)
        btnSend = findViewById(R.id.btnSend)
        btnStop = findViewById(R.id.btnStop)
        dotStatus = findViewById(R.id.dotStatus)
        tvHeaderStatus = findViewById(R.id.tvHeaderStatus)

        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        btnSend.setOnClickListener { sendTask() }
        btnStop.setOnClickListener { stopTask() }

        runCatching { rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuPermissionListener) }
        showWelcome()
    }

    override fun onResume() {
        super.onResume()
        refreshHeaderStatus()
    }

    override fun onDestroy() {
        destroyed = true
        runner?.cancel()
        runCatching { rikka.shizuku.Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener) }
        super.onDestroy()
    }

    // ==================== 欢迎区 ====================

    private fun showWelcome() {
        if (welcomeView != null) return
        val v = layoutInflater.inflate(R.layout.item_welcome, chatContainer, false)
        v.findViewById<View>(R.id.tvExample1).setOnClickListener { useExample(getString(R.string.chat_example_1)) }
        v.findViewById<View>(R.id.tvExample2).setOnClickListener { useExample(getString(R.string.chat_example_2)) }
        chatContainer.addView(v)
        welcomeView = v
    }

    private fun useExample(text: String) {
        etTask.setText(text)
        etTask.setSelection(text.length)
    }

    private fun dismissWelcome() {
        welcomeView?.let { chatContainer.removeView(it) }
        welcomeView = null
    }

    // ==================== 任务运行 ====================

    private fun sendTask() {
        val task = etTask.text.toString().trim()
        if (task.isEmpty()) {
            Toast.makeText(this, R.string.warn_need_task, Toast.LENGTH_SHORT).show()
            return
        }
        if (!AzAccessibilityService.isEnabled()) {
            Toast.makeText(this, R.string.warn_need_accessibility, Toast.LENGTH_LONG).show()
            return
        }
        if (AgentConfig.apiKey(this).isBlank()) {
            Toast.makeText(this, R.string.warn_need_apikey, Toast.LENGTH_LONG).show()
            return
        }

        dismissWelcome()
        etTask.setText("")
        addUserMessage(task)

        val r = AgentRunner(this) { event -> runOnUiThread { handleEvent(event) } }
        runner = r
        setRunning(true)

        worker = Thread({
            try {
                r.run(task)
            } catch (e: Exception) {
                runOnUiThread { handleEvent(AgentEvent.Failure(e.message ?: "任务异常结束")) }
            } finally {
                runOnUiThread {
                    if (!destroyed) {
                        runner = null
                        setRunning(false)
                    }
                }
            }
        }, "azcode-agent").apply { start() }
    }

    private fun stopTask() {
        runner?.cancel()
        btnStop.isEnabled = false
        addNotice(getString(R.string.stopping))
    }

    private fun setRunning(running: Boolean) {
        btnSend.visibility = if (running) View.GONE else View.VISIBLE
        btnStop.visibility = if (running) View.VISIBLE else View.GONE
        btnStop.isEnabled = running
        etTask.isEnabled = !running
    }

    // ==================== 事件处理 ====================

    private fun handleEvent(event: AgentEvent) {
        if (destroyed) return
        when (event) {
            is AgentEvent.Thinking -> showTyping()
            is AgentEvent.AssistantText -> {
                removeTyping()
                addAssistantMessage(event.text)
            }
            is AgentEvent.ToolStart -> {
                removeTyping()
                startToolCard(event.name, event.args)
            }
            is AgentEvent.ToolResult -> finishToolCard(event.ok, event.output)
            is AgentEvent.Notice -> {
                removeTyping()
                addNotice(event.text)
            }
            is AgentEvent.Failure -> {
                removeTyping()
                addError(getString(R.string.error_prefix, event.message))
            }
        }
    }

    private fun showTyping() {
        if (typingView != null) return
        val v = layoutInflater.inflate(R.layout.item_typing, chatContainer, false)
        chatContainer.addView(v)
        typingView = v
        scrollToBottom()
    }

    private fun removeTyping() {
        typingView?.let { chatContainer.removeView(it) }
        typingView = null
    }

    // ==================== 消息渲染 ====================

    private fun addUserMessage(text: String) {
        val v = layoutInflater.inflate(R.layout.item_msg_user, chatContainer, false)
        v.findViewById<TextView>(R.id.tvMsg).text = text
        chatContainer.addView(v)
        scrollToBottom()
    }

    private fun addAssistantMessage(text: String) {
        val v = layoutInflater.inflate(R.layout.item_msg_assistant, chatContainer, false)
        v.findViewById<TextView>(R.id.tvMsg).text = text
        chatContainer.addView(v)
        scrollToBottom()
    }

    private fun addNotice(text: String) {
        val v = layoutInflater.inflate(R.layout.item_msg_system, chatContainer, false)
        v.findViewById<TextView>(R.id.tvMsg).text = text
        chatContainer.addView(v)
        scrollToBottom()
    }

    private fun addError(text: String) {
        val v = layoutInflater.inflate(R.layout.item_msg_error, chatContainer, false)
        v.findViewById<TextView>(R.id.tvMsg).text = text
        chatContainer.addView(v)
        scrollToBottom()
    }

    private fun startToolCard(name: String, argsRaw: String) {
        activeTool = null
        val v = layoutInflater.inflate(R.layout.item_msg_tool, chatContainer, false)
        val card = ToolCard(v)
        v.findViewById<TextView>(R.id.tvToolName).text = toolLabel(name)
        card.status.text = getString(R.string.tool_running)
        card.status.setTextColor(colorRunning)
        card.args.text = prettyArgs(argsRaw)
        v.findViewById<View>(R.id.toolHeader).setOnClickListener { card.toggle() }
        chatContainer.addView(v)
        activeTool = card
        scrollToBottom()
    }

    private fun finishToolCard(ok: Boolean, output: String) {
        val card = activeTool ?: return
        card.status.text = getString(if (ok) R.string.tool_ok else R.string.tool_fail)
        card.status.setTextColor(if (ok) colorOk else colorFail)
        card.result.text = truncate(prettyResult(output), 3000)
        if (!ok) card.expand()
        activeTool = null
        scrollToBottom()
    }

    private class ToolCard(private val root: View) {
        val status: TextView = root.findViewById(R.id.tvToolStatus)
        val args: TextView = root.findViewById(R.id.tvToolArgs)
        val result: TextView = root.findViewById(R.id.tvToolResult)
        private val body: View = root.findViewById(R.id.toolBody)
        private val chevron: ImageView = root.findViewById(R.id.ivChevron)
        private var expanded = false

        fun toggle() {
            expanded = !expanded
            body.visibility = if (expanded) View.VISIBLE else View.GONE
            chevron.rotation = if (expanded) 180f else 0f
        }

        fun expand() {
            if (!expanded) toggle()
        }
    }

    // ==================== 格式化 ====================

    private fun toolLabel(name: String): String = when (name) {
        "get_screen" -> getString(R.string.tool_name_get_screen)
        "tap" -> getString(R.string.tool_name_tap)
        "swipe" -> getString(R.string.tool_name_swipe)
        "global" -> getString(R.string.tool_name_global)
        "shell" -> getString(R.string.tool_name_shell)
        "finish" -> getString(R.string.tool_name_finish)
        else -> name
    }

    private fun prettyArgs(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty() || t == "{}") return "（无）"
        return runCatching { JSONObject(t).toString(2) }.getOrDefault(t)
    }

    private fun prettyResult(raw: String): String {
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return raw
        obj.optString("error").takeIf { it.isNotBlank() }?.let { return it }
        obj.optString("output").takeIf { it.isNotBlank() }?.let { return it }
        obj.optString("summary").takeIf { it.isNotBlank() }?.let { return it }
        return runCatching { obj.toString(2) }.getOrDefault(raw)
    }

    private fun truncate(s: String, max: Int) =
        if (s.length <= max) s else s.take(max) + "\n…（已截断）"

    private fun scrollToBottom() {
        svChat.post { svChat.fullScroll(View.FOCUS_DOWN) }
    }

    private fun refreshHeaderStatus() {
        val on = AzAccessibilityService.isEnabled()
        dotStatus.setBackgroundResource(if (on) R.drawable.dot_on else R.drawable.dot_off)
        tvHeaderStatus.text =
            getString(if (on) R.string.status_accessibility_on else R.string.status_accessibility_off)
    }
}

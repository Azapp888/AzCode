package app.azcode.bridge

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/**
 * 聊天式主界面（DeepSeek 风格）：
 *  - 支持多个任务会话，聊天记录按会话持久化，可从历史中切换
 *  - 用户/助手消息以气泡呈现，支持图片与文档附件
 *  - 工具调用以可折叠卡片呈现，默认收起，失败时自动展开
 *  - 所有配置、技能与记忆入口收敛到 SettingsActivity
 */
class MainActivity : Activity() {

    private lateinit var chatContainer: LinearLayout
    private lateinit var svChat: ScrollView
    private lateinit var etTask: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var btnStop: ImageButton
    private lateinit var btnAttach: ImageButton
    private lateinit var dotStatus: View
    private lateinit var tvHeaderStatus: TextView
    private lateinit var tvHeaderTitle: TextView
    private lateinit var svAttachments: HorizontalScrollView
    private lateinit var attachmentsRow: LinearLayout

    @Volatile private var runner: AgentRunner? = null
    private var worker: Thread? = null
    @Volatile private var destroyed = false

    private lateinit var session: ChatSession
    private var lastLoadedId: String? = null
    private var typingView: View? = null
    private var activeTool: ToolCard? = null
    private var welcomeView: View? = null
    private val pending = mutableListOf<AttachmentReader.Pending>()

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
        btnAttach = findViewById(R.id.btnAttach)
        dotStatus = findViewById(R.id.dotStatus)
        tvHeaderStatus = findViewById(R.id.tvHeaderStatus)
        tvHeaderTitle = findViewById(R.id.tvHeaderTitle)
        svAttachments = findViewById(R.id.svAttachments)
        attachmentsRow = findViewById(R.id.attachmentsRow)

        findViewById<View>(R.id.btnSessions).setOnClickListener {
            startActivity(Intent(this, SessionsActivity::class.java))
        }
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        btnSend.setOnClickListener { sendTask() }
        btnStop.setOnClickListener { stopTask() }
        btnAttach.setOnClickListener { pickAttachments() }

        runCatching { rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuPermissionListener) }
        session = SessionStore.current(this)
        lastLoadedId = session.id
        renderSession()
        refreshAttachments()
    }

    override fun onResume() {
        super.onResume()
        refreshHeaderStatus()
        val currentId = SessionStore.current(this).id
        if (currentId != lastLoadedId && runner == null) {
            session = SessionStore.get(this, currentId) ?: SessionStore.current(this)
            lastLoadedId = session.id
            renderSession()
        } else {
            updateHeaderTitle()
        }
    }

    override fun onDestroy() {
        destroyed = true
        runner?.cancel()
        runCatching { rikka.shizuku.Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener) }
        super.onDestroy()
    }

    // ==================== 会话渲染 ====================

    private fun renderSession() {
        chatContainer.removeAllViews()
        typingView = null
        activeTool = null
        welcomeView = null
        updateHeaderTitle()

        if (session.turns.isEmpty()) {
            showWelcome()
            return
        }
        session.turns.forEach { renderTurn(it) }
        scrollToBottom()
    }

    private fun renderTurn(turn: ChatTurn) {
        when (turn.kind) {
            "user" -> addUserMessage(turn.text, turn.attachments, persist = false)
            "assistant" -> addAssistantMessage(turn.text, persist = false)
            "notice" -> addNotice(turn.text, persist = false)
            "error" -> addError(turn.text, persist = false)
            "tool" -> {
                startToolCard(turn.toolName, turn.toolArgs, persist = false)
                finishToolCard(turn.toolOk, turn.toolResult, persist = false)
            }
        }
    }

    private fun updateHeaderTitle() {
        tvHeaderTitle.text = session.title.ifBlank { getString(R.string.session_default_title) }
    }

    private fun persistSession() {
        SessionStore.save(this, session)
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

    // ==================== 附件 ====================

    private fun pickAttachments() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, AttachmentReader.PICK_MIME_TYPES)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_PICK)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return

        val uris = mutableListOf<Uri>()
        data.clipData?.let { clip: ClipData ->
            for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
        } ?: data.data?.let { uris.add(it) }

        var rejected = 0
        uris.forEach { uri ->
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val name = queryName(uri)
            val mime = contentResolver.getType(uri).orEmpty()
            try {
                val kind = AttachmentReader.kindOf(name, mime)
                if (pending.any { it.uri == uri }) return@forEach
                pending.add(AttachmentReader.Pending(uri, name, mime, kind))
            } catch (e: AttachmentReader.UnsupportedException) {
                rejected++
                Toast.makeText(this, e.message, Toast.LENGTH_SHORT).show()
            }
        }
        if (uris.isNotEmpty() && rejected == 0) refreshAttachments()
    }

    private fun refreshAttachments() {
        attachmentsRow.removeAllViews()
        svAttachments.visibility = if (pending.isEmpty()) View.GONE else View.VISIBLE
        pending.forEach { p ->
            val chip = layoutInflater.inflate(R.layout.item_attachment_chip, attachmentsRow, false) as TextView
            chip.text = p.name
            chip.setOnClickListener {
                pending.remove(p)
                refreshAttachments()
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.marginEnd = dp(6)
            chip.layoutParams = lp
            attachmentsRow.addView(chip)
        }
    }

    private fun queryName(uri: Uri): String {
        runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) {
                    return c.getString(idx) ?: "附件"
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/').orEmpty().ifEmpty { "附件" }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ==================== 任务运行 ====================

    private fun sendTask() {
        val task = etTask.text.toString().trim()
        if (task.isEmpty() && pending.isEmpty()) {
            Toast.makeText(this, R.string.warn_need_task, Toast.LENGTH_SHORT).show()
            return
        }
        if (AgentConfig.apiKey(this).isBlank()) {
            Toast.makeText(this, R.string.warn_need_apikey, Toast.LENGTH_LONG).show()
            return
        }

        val attachments = pending.toList()
        val displayTask = task.ifEmpty { "（见附件）" }
        pending.clear()
        refreshAttachments()

        dismissWelcome()
        etTask.setText("")
        addUserMessage(displayTask, attachments.map { it.name })

        val r = AgentRunner(this) { event -> runOnUiThread { handleEvent(event) } }
        runner = r
        val runningSession = session
        setRunning(true)

        worker = Thread({
            try {
                val prepared = ArrayList<AttachmentReader.Prepared>()
                for (p in attachments) {
                    try {
                        prepared.addAll(AttachmentReader.prepareAll(this, p))
                    } catch (e: Exception) {
                        runOnUiThread { handleEvent(AgentEvent.Failure("附件「${p.name}」读取失败：${e.message}")) }
                        return@Thread
                    }
                }
                r.run(displayTask, prepared, runningSession)
            } catch (e: Exception) {
                runOnUiThread { handleEvent(AgentEvent.Failure(e.message ?: "任务异常结束")) }
            } finally {
                SessionStore.save(this, runningSession)
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
        btnAttach.isEnabled = !running
        etTask.isEnabled = !running
        findViewById<View>(R.id.btnSessions).isEnabled = !running
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

    private fun appendTurn(turn: ChatTurn) {
        session.turns.add(turn)
        // 首条用户消息用于生成会话标题
        if (turn.kind == "user" && (session.title.isBlank() || session.title == getString(R.string.session_default_title))) {
            session.title = SessionStore.deriveTitle(turn.text)
            updateHeaderTitle()
        }
        persistSession()
    }

    private fun addUserMessage(text: String, attachmentNames: List<String> = emptyList(), persist: Boolean = true) {
        val v = layoutInflater.inflate(R.layout.item_msg_user, chatContainer, false)
        v.findViewById<TextView>(R.id.tvMsg).text = text
        if (attachmentNames.isNotEmpty()) {
            val tvAtt = v.findViewById<TextView>(R.id.tvAttachments)
            tvAtt.visibility = View.VISIBLE
            tvAtt.text = "附件：" + attachmentNames.joinToString("、")
        }
        chatContainer.addView(v)
        if (persist) appendTurn(ChatTurn(kind = "user", text = text, attachments = attachmentNames))
        scrollToBottom()
    }

    private fun addAssistantMessage(text: String, persist: Boolean = true) {
        val v = layoutInflater.inflate(R.layout.item_msg_assistant, chatContainer, false)
        v.findViewById<TextView>(R.id.tvMsg).text = text
        chatContainer.addView(v)
        if (persist) appendTurn(ChatTurn(kind = "assistant", text = text))
        scrollToBottom()
    }

    private fun addNotice(text: String, persist: Boolean = true) {
        val v = layoutInflater.inflate(R.layout.item_msg_system, chatContainer, false)
        v.findViewById<TextView>(R.id.tvMsg).text = text
        chatContainer.addView(v)
        if (persist) appendTurn(ChatTurn(kind = "notice", text = text))
        scrollToBottom()
    }

    private fun addError(text: String, persist: Boolean = true) {
        val v = layoutInflater.inflate(R.layout.item_msg_error, chatContainer, false)
        v.findViewById<TextView>(R.id.tvMsg).text = text
        chatContainer.addView(v)
        if (persist) appendTurn(ChatTurn(kind = "error", text = text))
        scrollToBottom()
    }

    private fun startToolCard(name: String, argsRaw: String, persist: Boolean = true) {
        activeTool = null
        val v = layoutInflater.inflate(R.layout.item_msg_tool, chatContainer, false)
        val card = ToolCard(v)
        v.findViewById<TextView>(R.id.tvToolName).text = toolLabel(name)
        card.status.text = getString(R.string.tool_running)
        card.status.setTextColor(colorRunning)
        card.args.text = prettyArgs(argsRaw)
        v.findViewById<View>(R.id.toolHeader).setOnClickListener { card.toggle() }
        chatContainer.addView(v)
        card.turnName = name
        card.turnArgs = argsRaw
        activeTool = card
        if (persist) appendTurn(ChatTurn(kind = "tool", toolName = name, toolArgs = argsRaw))
        scrollToBottom()
    }

    private fun finishToolCard(ok: Boolean, output: String, persist: Boolean = true) {
        val card = activeTool ?: return
        card.status.text = getString(if (ok) R.string.tool_ok else R.string.tool_fail)
        card.status.setTextColor(if (ok) colorOk else colorFail)
        card.result.text = truncate(prettyResult(output), 3000)
        if (!ok) card.expand()
        if (persist) {
            val last = session.turns.lastOrNull()
            if (last != null && last.kind == "tool") {
                session.turns[session.turns.lastIndex] = last.copy(toolOk = ok, toolResult = output)
                persistSession()
            }
        }
        activeTool = null
        scrollToBottom()
    }

    private class ToolCard(private val root: View) {
        val status: TextView = root.findViewById(R.id.tvToolStatus)
        val args: TextView = root.findViewById(R.id.tvToolArgs)
        val result: TextView = root.findViewById(R.id.tvToolResult)
        var turnName: String = ""
        var turnArgs: String = ""
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

    companion object {
        private const val REQ_PICK = 2001
    }
}

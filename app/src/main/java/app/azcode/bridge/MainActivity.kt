package app.azcode.bridge

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

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
    private lateinit var modelPanel: View
    private lateinit var btnModelBox: TextView
    private lateinit var spModel: Spinner
    private lateinit var depthRow: LinearLayout
    private var updatingModelSpinner = false

    @Volatile private var runner: AgentRunner? = null
    private var worker: Thread? = null
    @Volatile private var destroyed = false

    @Volatile private var stoppedByUser = false
    @Volatile private var taskError: String? = null
    @Volatile private var lastSummary: String? = null

    private lateinit var session: ChatSession
    private var lastLoadedId: String? = null
    private var typingView: View? = null
    private var activeTool: ToolCard? = null
    private var welcomeView: View? = null
    private val pending = mutableListOf<AttachmentReader.Pending>()
    @Volatile private var pendingQuestionLatch: CountDownLatch? = null
    private var lastBackPress = 0L

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
        modelPanel = findViewById(R.id.modelPanel)
        btnModelBox = findViewById(R.id.btnModelBox)
        spModel = findViewById(R.id.spModel)
        depthRow = findViewById(R.id.depthRow)

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
        setupModelSelector()
        session = SessionStore.current(this)
        lastLoadedId = session.id
        renderSession()
        refreshAttachments()
    }

    override fun onResume() {
        super.onResume()
        refreshHeaderStatus()
        syncModelSelector()
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
        pendingQuestionLatch?.countDown()
        runner?.cancel()
        runCatching { rikka.shizuku.Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener) }
        super.onDestroy()
    }

    /** 返回手势（含侧滑）默认会直接退出应用，改为两秒内二次返回才退出，避免误触。 */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val panel = findViewById<View>(R.id.questionPanel)
        if (panel.visibility == View.VISIBLE) {
            findViewById<TextView>(R.id.btnQuestionCancel).performClick()
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastBackPress < 2000) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        } else {
            lastBackPress = now
            Toast.makeText(this, R.string.back_again_to_exit, Toast.LENGTH_SHORT).show()
        }
    }

    // ==================== 向用户提问 ====================

    /**
     * 在工作线程上阻塞，等待用户在输入框下方的问答区作答；同时发送通知栏提醒。
     * 返回 null 表示未作答或界面已销毁。
     */
    private fun askUserBlocking(question: AgentQuestion): String? {
        TaskNotifier.notifyQuestion(this, question.question)
        val latch = CountDownLatch(1)
        val answer = AtomicReference<String?>(null)
        pendingQuestionLatch = latch
        runOnUiThread {
            if (destroyed) {
                latch.countDown()
                return@runOnUiThread
            }
            showQuestionPanel(question) { result ->
                answer.set(result)
                latch.countDown()
            }
        }
        runCatching { latch.await() }
        pendingQuestionLatch = null
        return answer.get()
    }

    /**
     * 在输入框下方的问答区展开内容：选择题、多选题或手动输入。
     * 作答或取消后收起区域并回调结果（null 表示未作答）。
     */
    private fun showQuestionPanel(question: AgentQuestion, onResult: (String?) -> Unit) {
        val panel = findViewById<View>(R.id.questionPanel)
        val title = findViewById<TextView>(R.id.tvQuestionTitle)
        val optionsBox = findViewById<LinearLayout>(R.id.questionOptions)
        val et = findViewById<EditText>(R.id.etQuestionCustom)
        val btnConfirm = findViewById<TextView>(R.id.btnQuestionConfirm)
        val btnCancel = findViewById<TextView>(R.id.btnQuestionCancel)

        optionsBox.removeAllViews()
        et.setText("")
        et.visibility = if (question.allowCustom) View.VISIBLE else View.GONE

        val prefix = if (question.total > 1) {
            getString(R.string.question_progress, question.index, question.total)
        } else ""
        title.text = prefix + question.question

        var confirmAction: () -> String? = { null }

        if (question.options.isNotEmpty() && question.allowMultiple) {
            val boxes = mutableListOf<CheckBox>()
            question.options.forEach { option ->
                val cb = CheckBox(this).apply {
                    text = option
                    textSize = 14f
                    setTextColor(getColor(R.color.text_primary))
                }
                boxes.add(cb)
                optionsBox.addView(cb)
            }
            confirmAction = {
                val picks = boxes.filter { it.isChecked }.map { it.text.toString() }.toMutableList()
                val custom = et.text.toString().trim()
                if (custom.isNotEmpty()) picks.add(custom)
                picks.joinToString("、").ifBlank { null }
            }
        } else if (question.options.isNotEmpty()) {
            val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
            val customId = View.generateViewId()
            if (question.allowCustom) {
                group.addView(RadioButton(this).apply {
                    id = customId
                    text = getString(R.string.question_custom_option)
                    textSize = 14f
                    setTextColor(getColor(R.color.text_primary))
                })
                et.hint = getString(R.string.question_custom_hint)
            }
            question.options.forEach { option ->
                group.addView(RadioButton(this).apply {
                    id = View.generateViewId()
                    text = option
                    textSize = 14f
                    setTextColor(getColor(R.color.text_primary))
                })
            }
            (group.getChildAt(if (question.allowCustom) 1 else 0) as RadioButton).isChecked = true
            optionsBox.addView(group)
            confirmAction = {
                val checkedId = group.checkedRadioButtonId
                val selectedView = group.findViewById<RadioButton>(checkedId)
                val custom = et.text.toString().trim()
                val isCustom = checkedId == customId
                when {
                    isCustom || checkedId == -1 -> custom.ifBlank { null }
                    custom.isNotEmpty() -> "${selectedView?.text}；$custom"
                    else -> selectedView?.text?.toString()?.ifBlank { null }
                }
            }
        } else {
            confirmAction = { et.text.toString().trim().ifBlank { null } }
        }

        var done = false
        val finish: (String?) -> Unit = { result ->
            if (!done) {
                done = true
                panel.visibility = View.GONE
                onResult(result)
            }
        }
        btnConfirm.setOnClickListener { finish(confirmAction()) }
        btnCancel.setOnClickListener { finish(null) }

        panel.visibility = View.VISIBLE
        scrollToBottom()
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
            "image" -> addImages(turn.images, persist = false)
        }
    }

    private fun updateHeaderTitle() {
        tvHeaderTitle.text = session.title.ifBlank { getString(R.string.session_default_title) }
    }

    private fun persistSession() {
        SessionStore.save(this, session)
    }

    // ==================== 模型 / 思考深度 ====================

    private var providerOptions: List<Pair<ProviderAccount, String>> = emptyList()
    private var providersSignature = ""

    private fun allProvidersSignature(): String = AgentConfig.providers(this)
        .joinToString("|") { "${it.id}:${it.name}:${it.enabled}:${it.allModels.joinToString(",")}" }

    /** 展开每个提供商下的全部语言模型，同一提供商的模型相邻，形成分组效果。 */
    private fun buildModelOptions(all: List<ProviderAccount>): List<Pair<ProviderAccount, String>> {
        val enabled = all.filter { it.enabled }.ifEmpty { all }
        val out = mutableListOf<Pair<ProviderAccount, String>>()
        enabled.forEach { account ->
            val models = account.allModels.ifEmpty { listOf(account.model.ifBlank { "" }) }
            models.forEach { out.add(account to it) }
        }
        return out
    }

    private fun modelOptionLabel(account: ProviderAccount, model: String): String =
        if (model.isBlank()) account.name else "${account.name} · $model"

    private fun buildModelAdapter(): ArrayAdapter<String> {
        return object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            providerOptions.map { modelOptionLabel(it.first, it.second) },
        ) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                (v as? TextView)?.textSize = 13f
                return v
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent)
                (v as? TextView)?.textSize = 13f
                return v
            }
        }
    }

    private fun setupModelSelector() {
        val all = AgentConfig.providers(this)
        providersSignature = all.joinToString("|") {
            "${it.id}:${it.name}:${it.enabled}:${it.allModels.joinToString(",")}"
        }
        providerOptions = buildModelOptions(all)
        val active = AgentConfig.activeProvider(this)
        val activeId = active?.id
        val activeModel = active?.model

        updatingModelSpinner = true
        spModel.adapter = buildModelAdapter()
        val index = providerOptions
            .indexOfFirst { it.first.id == activeId && it.second == activeModel }
            .let { if (it >= 0) it else providerOptions.indexOfFirst { it.first.id == activeId } }
            .coerceAtLeast(0)
        spModel.setSelection(index, false)
        updatingModelSpinner = false
        spModel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (updatingModelSpinner) return
                val (account, model) = providerOptions.getOrNull(position) ?: return
                if (account.id != AgentConfig.activeId(this@MainActivity)) {
                    AgentConfig.setActiveId(this@MainActivity, account.id)
                }
                if (model.isNotBlank() && model != account.model) {
                    AgentConfig.setActiveModel(this@MainActivity, account.id, model)
                }
                updateModelBox()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        depthRow.removeAllViews()
        ThinkingDepth.entries.forEach { depth ->
            val chip = TextView(this).apply {
                text = depth.label
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(6), dp(14), dp(6))
                setOnClickListener {
                    AgentConfig.setThinkingDepth(this@MainActivity, depth)
                    updateDepthChips()
                    updateModelBox()
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.marginEnd = dp(8)
            depthRow.addView(chip, lp)
        }

        btnModelBox.setOnClickListener {
            modelPanel.visibility = if (modelPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        updateDepthChips()
        updateModelBox()
    }

    private fun syncModelSelector() {
        if (allProvidersSignature() != providersSignature) {
            setupModelSelector()
            return
        }
        val active = AgentConfig.activeProvider(this)
        val activeId = active?.id
        val activeModel = active?.model
        val index = providerOptions
            .indexOfFirst { it.first.id == activeId && it.second == activeModel }
            .let { if (it >= 0) it else providerOptions.indexOfFirst { it.first.id == activeId } }
        if (index >= 0 && index != spModel.selectedItemPosition) {
            updatingModelSpinner = true
            spModel.setSelection(index, false)
            updatingModelSpinner = false
        }
        updateDepthChips()
        updateModelBox()
    }

    private fun updateDepthChips() {
        val current = AgentConfig.thinkingDepth(this)
        for (i in 0 until depthRow.childCount) {
            val chip = depthRow.getChildAt(i) as TextView
            val selected = ThinkingDepth.entries[i] == current
            chip.background = getDrawable(if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip)
            chip.setTextColor(getColor(if (selected) R.color.text_on_primary else R.color.text_secondary))
        }
    }

    private fun updateModelBox() {
        val provider = AgentConfig.activeProvider(this)
        val model = provider?.model?.takeIf { it.isNotBlank() } ?: getString(R.string.model_box_unset)
        btnModelBox.text = getString(
            R.string.model_box_format,
            model,
            AgentConfig.thinkingDepth(this).label,
        )
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

        maybeRequestNotificationPermission()

        stoppedByUser = false
        taskError = null
        lastSummary = null
        val r = AgentRunner(this, { question -> askUserBlocking(question) }) { event ->
            when (event) {
                is AgentEvent.Failure -> taskError = event.message
                is AgentEvent.AssistantText -> if (event.text.isNotBlank()) lastSummary = event.text
                else -> {}
            }
            runOnUiThread { handleEvent(event) }
        }
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
                val success = taskError == null && !stoppedByUser
                TaskNotifier.notifyFinished(this, runningSession.title, lastSummary, success)
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
        stoppedByUser = true
        runner?.cancel()
        btnStop.isEnabled = false
        addNotice(getString(R.string.stopping))
    }

    private fun maybeRequestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
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
            is AgentEvent.Images -> {
                removeTyping()
                addImages(event.urls, persist = true)
            }
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
        val (md, images) = Markdown.extractImages(text)
        if (md.isNotBlank()) {
            val v = layoutInflater.inflate(R.layout.item_msg_assistant, chatContainer, false)
            Markdown.render(v.findViewById(R.id.tvMsg), md)
            chatContainer.addView(v)
        }
        if (persist) appendTurn(ChatTurn(kind = "assistant", text = text))
        addImages(images, persist = false)
        scrollToBottom()
    }

    /** 渲染一组图片气泡；persist=true 时把图片地址写入会话以便重建。 */
    private fun addImages(urls: List<String>, persist: Boolean) {
        if (urls.isEmpty()) return
        urls.forEach { url ->
            val v = layoutInflater.inflate(R.layout.item_msg_image, chatContainer, false)
            val iv = v.findViewById<ImageView>(R.id.ivImage)
            ImageLoader.load(iv, url)
            iv.setOnClickListener { showImageDialog(url) }
            chatContainer.addView(v)
        }
        if (persist) {
            val stored = urls.map { ImageLoader.persist(this, it) }
            appendTurn(ChatTurn(kind = "image", images = stored))
        }
        scrollToBottom()
    }

    /** 全屏查看图片，并提供保存到相册的入口。 */
    private fun showImageDialog(url: String) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(24))
            setBackgroundColor(Color.BLACK)
        }
        val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        container.addView(
            iv,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        val btn = Button(this).apply { text = getString(R.string.image_download) }
        container.addView(
            btn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(12)
            },
        )

        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(container)
        ImageLoader.load(iv, url)

        btn.setOnClickListener {
            Toast.makeText(this, R.string.image_downloading, Toast.LENGTH_SHORT).show()
            Thread {
                val ok = ImageLoader.saveToGallery(this, url)
                runOnUiThread {
                    Toast.makeText(
                        this,
                        if (ok) R.string.image_saved else R.string.image_save_failed,
                        Toast.LENGTH_SHORT,
                    ).show()
                    if (ok) dialog.dismiss()
                }
            }.start()
        }
        dialog.show()
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
        "generate_image" -> getString(R.string.tool_name_generate_image)
        "ask_question_for_user" -> getString(R.string.tool_name_ask)
        "list_model_providers" -> getString(R.string.tool_name_list_providers)
        "fetch_models" -> getString(R.string.tool_name_fetch_models)
        "save_model_provider" -> getString(R.string.tool_name_save_provider)
        "remove_model_provider" -> getString(R.string.tool_name_remove_provider)
        "import_providers_md" -> getString(R.string.tool_name_import_md)
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
        private const val REQ_NOTIF = 2002
    }
}

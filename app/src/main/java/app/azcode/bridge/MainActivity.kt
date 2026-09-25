package app.azcode.bridge

import android.app.AlertDialog
import android.app.Dialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.drawerlayout.widget.DrawerLayout
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.drawable.ColorDrawable
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 聊天式主界面（DeepSeek 风格）：
 *  - 支持多个任务会话，聊天记录按会话持久化，可从历史中切换
 *  - 用户/助手消息以气泡呈现，支持图片与文档附件
 *  - 工具调用以可折叠卡片呈现，默认收起，失败时自动展开
 *  - 所有配置、技能与记忆入口收敛到 SettingsActivity
 */
class MainActivity : AppCompatActivity() {

    private lateinit var chatContainer: LinearLayout
    private lateinit var svChat: ScrollView
    private lateinit var etTask: PressToTalkEditText
    private lateinit var btnSend: ImageButton
    private lateinit var btnStop: ImageButton
    private lateinit var btnAttach: ImageButton
    private lateinit var tvHeaderTitle: TextView
    private lateinit var svAttachments: HorizontalScrollView
    private lateinit var attachmentsRow: LinearLayout
    private lateinit var btnModelBox: TextView
    private lateinit var sessionsContainer: LinearLayout
    private lateinit var tvSessionsEmpty: TextView
    private lateinit var tvSessionsCount: TextView
    private lateinit var svHistory: ScrollView
    private lateinit var rowSettings: View
    private lateinit var drawerRoot: DrawerLayout
    private var drawerAnimated = false
    private lateinit var tvDepthLabel: TextView
    private lateinit var depthSlider: DepthSliderView
    private var modelPopup: PopupWindow? = null
    private var pickerContainer: LinearLayout? = null
    private var pickerScroll: ScrollView? = null
    private var cameraOutputUri: Uri? = null

    private var speechEngine: SpeechEngine? = null
    private var micListening = false
    private var micBase = ""
    // 按住输入框说话的会话状态：按住开始识别，松手提交。
    private var pttActive = false
    private var pttPendingSend = false
    private var autoTaskConsumed = false
    private lateinit var voicePanel: View
    private lateinit var tvVoicePreview: TextView
    private lateinit var voiceWave: VoiceWaveView
    private lateinit var llVoiceSuggest: LinearLayout

    private val takePicture =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            if (ok) cameraOutputUri?.let { handlePickedUris(listOf(it)) }
        }

    private val pickImages =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (!uris.isNullOrEmpty()) handlePickedUris(uris)
        }

    private val pickFiles =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (!uris.isNullOrEmpty()) handlePickedUris(uris)
        }

    private val requestMic =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                if (!destroyed && !isFinishing) {
                    runCatching { startListening() }
                        .onFailure { t ->
                            CrashLog.e(TAG, "麦克风授权后启动识别失败", t)
                            Toast.makeText(
                                this,
                                "语音启动失败：" + (t.message ?: t.javaClass.simpleName),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                }
            } else {
                Toast.makeText(this, R.string.warn_need_mic, Toast.LENGTH_SHORT).show()
            }
        }

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                launchCamera()
            } else {
                Toast.makeText(this, R.string.warn_need_camera, Toast.LENGTH_SHORT).show()
            }
        }
    private var depthIndex = 0
    private var maxDepthConfirmed = false
    private var maxDepthPrompting = false

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
        voicePanel = findViewById(R.id.voicePanel)
        tvVoicePreview = findViewById(R.id.tvVoicePreview)
        voiceWave = findViewById(R.id.voiceWave)
        llVoiceSuggest = findViewById(R.id.llVoiceSuggest)
        tvHeaderTitle = findViewById(R.id.tvHeaderTitle)
        svAttachments = findViewById(R.id.svAttachments)
        attachmentsRow = findViewById(R.id.attachmentsRow)
        btnModelBox = findViewById(R.id.btnModelBox)
        sessionsContainer = findViewById(R.id.sessionsContainer)
        tvSessionsEmpty = findViewById(R.id.tvSessionsEmpty)
        tvSessionsCount = findViewById(R.id.tvSessionsCount)
        svHistory = findViewById(R.id.svHistory)
        rowSettings = findViewById(R.id.rowSettings)
        drawerRoot = findViewById(R.id.drawerRoot)
        drawerRoot.setScrimColor(getColor(R.color.scrim))

        findViewById<View>(R.id.btnMenu).setOnClickListener {
            drawerRoot.openDrawer(findViewById<View>(R.id.historyPanel))
        }
        findViewById<View>(R.id.btnHistoryClose).setOnClickListener { closeHistory() }
        findViewById<View>(R.id.btnNewChat).setOnClickListener { newSession() }
        rowSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        drawerRoot.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            // 在抽屉开始滑入时就让内容淡入，动画与面板同步，而不是打开完才播。
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                if (slideOffset > 0.02f) animateDrawerContent()
            }

            override fun onDrawerClosed(drawerView: View) {
                drawerAnimated = false
            }
        })
        btnSend.setOnClickListener { sendTask() }
        btnStop.setOnClickListener { stopTask() }
        btnAttach.setOnClickListener { showAttachmentMenu() }
        // 按住输入框说话：长按进入识别，松手立即提交（按住说话）。
        // 放到下一帧执行，避免在长按/触摸回调里直接拉起权限页造成异常；任何异常都落盘并提示，不闪退。
        etTask.onLongPressVoice = {
            etTask.post {
                if (!destroyed && !micListening) {
                    runCatching {
                        pttActive = true
                        ensureMicPermissionAndStart()
                    }
                        .onFailure { t ->
                            pttActive = false
                            CrashLog.e(TAG, "长按语音启动失败", t)
                            Toast.makeText(
                                this,
                                "语音启动失败：" + (t.message ?: t.javaClass.simpleName),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                }
            }
            true
        }
        // 松手 = 说完了 → 停止识别并自动发送。
        etTask.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP && pttActive) {
                pttActive = false
                if (micListening) {
                    pttPendingSend = true
                    stopListening()
                } else {
                    sendPttIfAny()
                }
            }
            false
        }
        findViewById<View>(R.id.btnVoiceKeyboard).setOnClickListener { switchToKeyboard() }

        runCatching { SkillStore.seedBuiltins(applicationContext) }
        runCatching { rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuPermissionListener) }
        setupModelSelector()
        session = SessionStore.current(this)
        lastLoadedId = session.id
        renderSession()
        refreshAttachments()
        refreshDrawer()
        // 全屏助理说完后把识别文本带回来，这里直接把它当作任务发送。
        maybeAutoTaskFromIntent()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // singleTask 下重复唤起不会重建 Activity，这里同样接收助理带回来的任务。
        maybeAutoTaskFromIntent()
    }

    /**
     * 接收全屏助理（AssistActivity）提交回来的识别文本并自动发送。
     *
     * 用 [autoTaskConsumed] 去重，避免配置变更/重建时重复发送同一任务。
     */
    private fun maybeAutoTaskFromIntent() {
        if (autoTaskConsumed) return
        val task = intent?.getStringExtra(EXTRA_AUTO_TASK)?.trim().orEmpty()
        if (task.isEmpty()) return
        autoTaskConsumed = true
        intent?.removeExtra(EXTRA_AUTO_TASK)
        etTask.post {
            if (destroyed) return@post
            etTask.setText(task)
            etTask.setSelection(etTask.length())
            sendTask()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshDrawer()
        syncModelSelector()
        maybeShowPersonalizationPrompt()
        // 用 SharedPreferences 中的当前 id 判断即可，无需读取解析会话文件。
        val currentId = SessionStore.currentId(this)
        if (currentId != null && currentId != lastLoadedId && runner == null) {
            session = SessionStore.get(this, currentId) ?: SessionStore.current(this)
            lastLoadedId = session.id
            renderSession()
        } else {
            updateHeaderTitle()
        }
    }

    /**
     * 首启且用户已开启无障碍服务后，展示一次个性化数据授权说明。
     * 说明只弹一次；用户可随时在「设置 → 个性化与隐私」重新查看。
     */
    private fun maybeShowPersonalizationPrompt() {
        if (Personalization.promptShown(this)) return
        if (!AzAccessibilityService.isEnabled()) return
        if (!canShowUi()) return
        Personalization.markPromptShown(this)
        PersonalizationActivity.showConsentDialog(this) {
            scanExecutor.execute { runCatching { Personalization.refreshSnapshot(applicationContext) } }
        }
    }

    override fun onDestroy() {
        destroyed = true
        pendingQuestionLatch?.countDown()
        runner?.cancel()
        runCatching { speechEngine?.release() }
        speechEngine = null
        // 让排队中的会话写入先落盘，再释放线程（不阻塞主线程）。
        runCatching { saveExecutor.shutdown() }
        runCatching { thumbExecutor.shutdown() }
        runCatching { rikka.shizuku.Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener) }
        super.onDestroy()
    }

    /** 弹窗前确认 Activity 仍可用，避免已销毁后 show 抛 BadTokenException 导致闪退。 */
    private fun canShowUi(): Boolean = !destroyed && !isFinishing && !isDestroyed

    /** 返回手势（含侧滑）默认会直接退出应用，改为两秒内二次返回才退出，避免误触。 */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (drawerRoot.isDrawerOpen(findViewById<View>(R.id.historyPanel))) {
            closeHistory()
            return
        }
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
        TaskNotifier.notifyQuestion(this, question)
        val latch = CountDownLatch(1)
        val answer = AtomicReference<String?>(null)
        pendingQuestionLatch = latch
        // 通知栏作答通道：用户在常驻通知里点选或输入后，与下方问答区共用同一份答案，先到者生效。
        QuestionBus.register { result ->
            if (answer.compareAndSet(null, result)) {
                runOnUiThread { runCatching { findViewById<View>(R.id.questionPanel)?.visibility = View.GONE } }
                latch.countDown()
            }
        }
        runOnUiThread {
            if (!canShowUi()) {
                latch.countDown()
                return@runOnUiThread
            }
            showQuestionPanel(question) { result ->
                if (answer.compareAndSet(null, result)) latch.countDown()
            }
        }
        // 分段等待并检查取消/销毁，避免用户点停止或退出后仍永久阻塞工作线程。
        while (latch.count > 0) {
            if (destroyed || runner?.isCancelled == true) break
            if (runCatching { latch.await(150, TimeUnit.MILLISECONDS) }.getOrDefault(false)) break
        }
        QuestionBus.clear()
        TaskNotifier.cancelQuestion(this)
        pendingQuestionLatch = null
        // 等待期间若被取消，收起未作答的问答区，避免残留面板。
        if (latch.count > 0L) runOnUiThread { runCatching { findViewById<View>(R.id.questionPanel)?.visibility = View.GONE } }
        return if (latch.count == 0L) answer.get() else null
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
        Markdown.render(title, prefix + question.question)

        var confirmAction: () -> String? = { null }

        if (question.options.isNotEmpty() && question.allowMultiple) {
            val boxes = mutableListOf<CheckBox>()
            question.options.forEach { option ->
                val cb = CheckBox(this).apply {
                    text = Markdown.toSpanned(this@MainActivity, option)
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
                    text = Markdown.toSpanned(this@MainActivity, option)
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
        // 批量渲染：整个会话重建期间挂起布局与绘制，避免每条消息各触发一次
        // measure/layout（长会话 + 公式渲染时是切换卡顿的主要来源）。
        val snapshot = synchronized(session.turns) { session.turns.toList() }
        svChat.suppressLayout(true)
        try {
            snapshot.forEach { renderTurn(it) }
        } finally {
            svChat.suppressLayout(false)
        }
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
            "document" -> addDocument(
                path = turn.attachments.firstOrNull().orEmpty(),
                name = turn.text,
                pages = turn.images,
                persist = false,
            )
        }
    }

    private fun updateHeaderTitle() {
        tvHeaderTitle.text = session.title.ifBlank { getString(R.string.session_default_title) }
    }

    /**
     * 会话保存移到单线程执行器：消息渲染不再被磁盘 I/O 阻塞（长会话下易触发 ANR），
     * 单线程也保证同一会话的写入顺序，配合 [SessionStore.save] 的原子写避免文件损坏。
     */
    private val saveExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "azcode-save").apply { isDaemon = true }
    }

    private fun persistSession() {
        val snapshot = session
        val app = applicationContext
        saveExecutor.execute {
            runCatching { SessionStore.save(app, snapshot) }
                .onFailure { CrashLog.w(TAG, "保存会话失败: ${it.message}", it) }
        }
    }

    /** 历史列表扫描线程：读取磁盘与解析 JSON 不占用主线程。 */
    private val scanExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "azcode-drawer").apply { isDaemon = true }
    }

    /** 防止历史列表重复排队扫描。 */
    @Volatile
    private var drawerScanning = false

    /** 会话加载线程：内存未命中时后台读盘，避免切换会话阻塞主线程。 */
    private val loadExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "azcode-load").apply { isDaemon = true }
    }

    /** 附件缩略图解码线程：仅用于附件栏小图，避免解码占用主线程。 */
    private val thumbExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "azcode-thumb").apply { isDaemon = true }
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

    private fun depthToProgress(index: Int): Float {
        val last = (ThinkingDepth.entries.size - 1).coerceAtLeast(1)
        return (index.coerceIn(0, last)).toFloat() / last
    }

    private fun setupModelSelector() {
        providersSignature = allProvidersSignature()
        providerOptions = buildModelOptions(AgentConfig.providers(this))
        val depth = AgentConfig.thinkingDepth(this)
        depthIndex = depth.ordinal
        maxDepthConfirmed = depth == ThinkingDepth.entries.last()

        setupModelPopup()

        btnModelBox.setOnClickListener { showModelPopup() }
        updateModelBox()
    }

    /** 预先构建模型/思考深度弹层：锚定在模型按钮上方，玻璃圆角面板。 */
    private fun setupModelPopup() {
        val content = layoutInflater.inflate(R.layout.popup_model_picker, null)
        pickerContainer = content.findViewById(R.id.pickerContainer)
        pickerScroll = content.findViewById(R.id.pickerScroll)
        tvDepthLabel = content.findViewById(R.id.tvDepthLabel)
        depthSlider = content.findViewById(R.id.depthSlider)

        // 连续滑块，无档位吸附；仅回报最接近的档位。
        depthSlider.depthCount = ThinkingDepth.entries.size
        depthSlider.setProgressSilently(depthToProgress(AgentConfig.thinkingDepth(this).ordinal))
        depthSlider.onProgressChanged = { _, index, fromUser -> onDepthChanged(index, fromUser) }
        updateDepthLabel(AgentConfig.thinkingDepth(this).ordinal)

        val popup = PopupWindow(
            content,
            dp(300),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            isFocusable = true
            isOutsideTouchable = true
            elevation = dp(8).toFloat()
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        modelPopup = popup
    }

    /** 在模型按钮上方弹出选择面板；上方空间不足时改为向下弹出。 */
    private fun showModelPopup() {
        val popup = modelPopup ?: return
        if (popup.isShowing) {
            popup.dismiss()
            return
        }
        syncModelSelector()
        buildPickerRows()

        val content = popup.contentView
        content.measure(
            View.MeasureSpec.makeMeasureSpec(dp(300), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val popupHeight = content.measuredHeight
        val loc = IntArray(2)
        btnModelBox.getLocationOnScreen(loc)
        val spaceAbove = loc[1]
        val gap = dp(6)

        if (spaceAbove > popupHeight + gap) {
            popup.showAsDropDown(
                btnModelBox,
                0,
                -(popupHeight + btnModelBox.height + gap),
                Gravity.START,
            )
        } else {
            popup.showAsDropDown(btnModelBox, 0, gap, Gravity.START)
        }
    }

    /** 刷新弹层内的提供商分组与模型条目，列表过长时压缩为可滚动区域。 */
    private fun buildPickerRows() {
        val container = pickerContainer ?: return
        val scroll = pickerScroll ?: return
        container.removeAllViews()

        val activeId = AgentConfig.activeId(this)
        val activeModel = AgentConfig.activeProvider(this)?.model
        if (providerOptions.isEmpty()) {
            container.addView(pickerHeader(getString(R.string.model_picker_empty)))
            scroll.layoutParams = scroll.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
            return
        }
        var lastProviderId: String? = null
        providerOptions.forEach { (account, model) ->
            if (account.id != lastProviderId) {
                lastProviderId = account.id
                container.addView(pickerHeader(account.name))
            }
            val selected = account.id == activeId && model == activeModel
            container.addView(
                pickerRow(model.ifBlank { account.name }, selected) {
                    if (account.id != AgentConfig.activeId(this)) {
                        AgentConfig.setActiveId(this, account.id)
                    }
                    if (model.isNotBlank() && model != account.model) {
                        AgentConfig.setActiveModel(this, account.id, model)
                    }
                    syncModelSelector()
                    modelPopup?.dismiss()
                },
            )
        }

        // 在展示前先把列表高度压到上限，避免弹出后再调整导致面板跳动。
        val maxList = dp(260)
        scroll.measure(
            View.MeasureSpec.makeMeasureSpec(dp(280), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val listHeight = scroll.measuredHeight.coerceAtMost(maxList)
        scroll.layoutParams = scroll.layoutParams.apply { height = listHeight }
    }

    private fun onDepthChanged(index: Int, fromUser: Boolean) {
        val last = ThinkingDepth.entries.size - 1
        if (!fromUser) {
            updateDepthLabel(index)
            return
        }
        if (index == last && !maxDepthConfirmed && depthIndex != last) {
            if (!maxDepthPrompting) promptMaxDepth()
            return
        }
        if (index != last) maxDepthConfirmed = false
        applyDepth(index)
    }

    private fun applyDepth(index: Int) {
        val depth = ThinkingDepth.entries[index.coerceIn(0, ThinkingDepth.entries.size - 1)]
        if (AgentConfig.thinkingDepth(this) != depth) AgentConfig.setThinkingDepth(this, depth)
        depthIndex = index
        updateDepthLabel(index)
        updateModelBox()
    }

    /** 拉满思考深度前，用与页面一致的玻璃卡片弹窗提示可能的额外费用。 */
    private fun promptMaxDepth() {
        maxDepthPrompting = true
        if (!canShowUi()) return
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val content = layoutInflater.inflate(R.layout.dialog_depth_confirm, null)
        dialog.setContentView(content)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnCancelListener {
            maxDepthPrompting = false
            revertDepth()
        }
        content.findViewById<View>(R.id.btnDepthCancel).setOnClickListener {
            maxDepthPrompting = false
            revertDepth()
            dialog.dismiss()
        }
        content.findViewById<View>(R.id.btnDepthConfirm).setOnClickListener {
            maxDepthPrompting = false
            maxDepthConfirmed = true
            applyDepth(ThinkingDepth.entries.size - 1)
            dialog.dismiss()
        }
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.45f)
        }
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.86f).toInt().coerceAtMost(dp(420)),
            WindowManager.LayoutParams.WRAP_CONTENT,
        )
        // 轻微放大淡入，和整体液态玻璃风格一致。
        content.alpha = 0f
        content.scaleX = 0.92f
        content.scaleY = 0.92f
        content.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(180L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun revertDepth() {
        depthSlider.setProgressSilently(depthToProgress(depthIndex))
        updateDepthLabel(depthIndex)
    }

    private fun updateDepthLabel(index: Int) {
        tvDepthLabel.text = ThinkingDepth.entries[index.coerceIn(0, ThinkingDepth.entries.size - 1)].label
    }

    private fun pickerHeader(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(getColor(R.color.text_caption))
        setPadding(dp(14), dp(14), dp(14), dp(4))
    }

    private fun pickerRow(text: String, selected: Boolean, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44)
            background = getDrawable(
                if (selected) R.drawable.bg_picker_item_selected else R.drawable.bg_picker_item,
            )
            isClickable = true
            isFocusable = true
            setPadding(dp(14), dp(10), dp(12), dp(10))
            setOnClickListener { onClick() }
        }
        val label = TextView(this).apply {
            this.text = text
            textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(getColor(if (selected) R.color.primary else R.color.text_primary))
        }
        row.addView(
            label,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (selected) {
            row.addView(
                ImageView(this).apply {
                    setImageResource(R.drawable.ic_check)
                    contentDescription = null
                },
                LinearLayout.LayoutParams(dp(18), dp(18)),
            )
        }
        return row
    }

    private fun syncModelSelector() {
        val all = AgentConfig.providers(this)
        val sig = allProvidersSignature()
        if (sig != providersSignature) {
            providersSignature = sig
            providerOptions = buildModelOptions(all)
        }
        val depth = AgentConfig.thinkingDepth(this)
        depthIndex = depth.ordinal
        if (depth != ThinkingDepth.entries.last()) maxDepthConfirmed = false
        depthSlider.setProgressSilently(depthToProgress(depth.ordinal))
        updateDepthLabel(depth.ordinal)
        updateModelBox()
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

    /** 在附件按钮上方弹出「拍照 / 相册 / 文件」三个小气泡。 */
    private fun showAttachmentMenu() {
        val content = layoutInflater.inflate(R.layout.popup_attachments, null)
        val popup = PopupWindow(
            content,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            isFocusable = true
            isOutsideTouchable = true
            elevation = dp(8).toFloat()
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }

        content.findViewById<View>(R.id.optCamera).setOnClickListener {
            popup.dismiss()
            ensureCameraThenCapture()
        }
        content.findViewById<View>(R.id.optGallery).setOnClickListener {
            popup.dismiss()
            pickImages.launch("image/*")
        }
        content.findViewById<View>(R.id.optFile).setOnClickListener {
            popup.dismiss()
            // 用 */* 让系统文件选择器展示全部文件：部分厂商选择器只按首个 MIME 过滤，
            // 传具体类型会导致 Excel / 音频等被隐藏；具体类型由 kindOf 校验并给出提示。
            pickFiles.launch(arrayOf("*/*"))
        }

        content.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val popupHeight = content.measuredHeight
        val gap = dp(8)

        // 锚定在输入框上方：左边缘与附件按钮对齐，整体位于输入区之上。
        val anchor = findViewById<View>(R.id.inputBar)
        val anchorLoc = IntArray(2)
        anchor.getLocationOnScreen(anchorLoc)
        val btnLoc = IntArray(2)
        btnAttach.getLocationOnScreen(btnLoc)
        val xOff = btnLoc[0] - anchorLoc[0]

        popup.showAsDropDown(anchor, xOff, -(popupHeight + anchor.height + gap), Gravity.START)
    }

    private fun ensureCameraThenCapture() {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchCamera()
        } else {
            requestCamera.launch(android.Manifest.permission.CAMERA)
        }
    }

    /** 用 FileProvider 生成临时文件后调起系统相机。 */
    private fun launchCamera() {
        runCatching {
            val dir = File(cacheDir, "captures").apply { mkdirs() }
            val file = File(dir, "IMG_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            cameraOutputUri = uri
            takePicture.launch(uri)
        }.onFailure {
            Toast.makeText(this, it.message ?: "无法启动相机", Toast.LENGTH_SHORT).show()
        }
    }

    /** 统一处理相册 / 文件 / 拍照返回的 Uri 列表。 */
    private fun handlePickedUris(uris: List<Uri>) {
        var rejected = 0
        uris.forEach { uri ->
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
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
            val chip = layoutInflater.inflate(R.layout.item_attachment_chip, attachmentsRow, false)
            val icon = chip.findViewById<ImageView>(R.id.ivAttachIcon)
            chip.findViewById<TextView>(R.id.tvAttachName).text = p.name
            if (p.kind == AttachmentReader.Kind.IMAGE) {
                loadThumbnail(p, icon)
            } else {
                icon.setImageResource(R.drawable.ic_file_generic)
                icon.imageTintList = ColorStateList.valueOf(fileTint(p.name, p.kind))
            }
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

    /** 图片附件显示真实缩略图，让用户确认选中的是不是想发的那张。 */
    private fun loadThumbnail(p: AttachmentReader.Pending, icon: ImageView) {
        icon.tag = p.uri
        icon.setImageResource(R.drawable.ic_image)
        thumbExecutor.execute {
            val bmp = runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(p.uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                val target = dp(72).coerceAtLeast(1)
                var sample = 1
                while (bounds.outWidth / sample > target || bounds.outHeight / sample > target) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                contentResolver.openInputStream(p.uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            }.getOrNull()
            runOnUiThread {
                if (destroyed || icon.tag != p.uri) return@runOnUiThread
                if (bmp != null) icon.setImageBitmap(bmp)
            }
        }
    }

    /** 非图片附件按类型着色，用同一个文件图标区分 PDF / 表格 / 文档 / 音频等。 */
    private fun fileTint(name: String, kind: AttachmentReader.Kind): Int {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            kind == AttachmentReader.Kind.AUDIO -> 0xFF8A5CF6.toInt()
            ext == "pdf" -> 0xFFE5484D.toInt()
            ext in setOf("xlsx", "xls", "csv") -> 0xFF1E9E5A.toInt()
            ext in setOf("docx", "doc") -> 0xFF2B6CF6.toInt()
            ext in setOf("pptx", "ppt") -> 0xFFE8890C.toInt()
            else -> 0xFF6B7280.toInt()
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
        // 记录用户命令，供语音输入页左下角推荐「常用/最近」命令。
        CommandMemory.record(this, task)

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
        CrashLog.i(TAG, "任务开始 session=${runningSession.id} 附件=${attachments.size} 模型=${AgentConfig.activeProvider(this)?.model}")

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
                CrashLog.e(TAG, "任务线程异常: ${e.message}", e)
                runOnUiThread { if (!destroyed) handleEvent(AgentEvent.Failure(e.message ?: "任务异常结束")) }
            } finally {
                // 保存与通知即使失败也不能让线程静默退出，否则 UI 会一直停在「运行中」。
                AzAccessibilityService.clearOverlays()
                runCatching { SessionStore.save(this, runningSession) }
                    .onFailure { CrashLog.w(TAG, "任务结束保存会话失败: ${it.message}", it) }
                runCatching {
                    val success = taskError == null && !stoppedByUser
                    TaskNotifier.notifyFinished(this, runningSession.title, lastSummary, success)
                }.onFailure { CrashLog.w(TAG, "任务结束通知失败: ${it.message}", it) }
                runOnUiThread {
                    if (!destroyed) {
                        runner = null
                        setRunning(false)
                    }
                }
                CrashLog.i(TAG, "任务结束 stopped=$stoppedByUser error=$taskError")
            }
        }, "azcode-agent").apply { start() }
    }

    private fun stopTask() {
        stoppedByUser = true
        runner?.cancel()
        // 立即释放正在等待作答的问答区，让工作线程马上从等待中返回。
        pendingQuestionLatch?.countDown()
        AzAccessibilityService.clearOverlays()
        btnStop.isEnabled = false
        addNotice(getString(R.string.stopping))
    }

    private fun maybeRequestNotificationPermission() {
        TaskNotifier.ensureChannel(this)
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
    }

    // ==================== 语音输入 ====================

    private fun ensureMicPermissionAndStart() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startListening() {
        val engine = speechEngine ?: SpeechEngines.create(this).also { speechEngine = it }
        if (!engine.isAvailable(this)) {
            Toast.makeText(this, R.string.warn_mic_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        // 记录进入识别前输入框已有内容，识别结果追加在其后。
        micBase = etTask.text.toString()
        showVoicePanel()
        engine.start(this, speechListener)
    }

    private fun stopListening() {
        runCatching { speechEngine?.stop() }
    }

    /** 展示底部语音面板：波动条 + 实时识别预览 + 左下角推荐命令 + 右下角切键盘。 */
    private fun showVoicePanel() {
        tvVoicePreview.text = getString(R.string.voice_hold_send)
        renderVoiceSuggestions()
        voicePanel.visibility = View.VISIBLE
        voiceWave.start()
        runCatching {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(etTask.windowToken, 0)
        }
    }

    private fun hideVoicePanel() {
        voiceWave.stop()
        voicePanel.visibility = View.GONE
    }

    private fun renderVoiceSuggestions() {
        llVoiceSuggest.removeAllViews()
        CommandMemory.frequent(this, 4).forEach { cmd ->
            val chip = TextView(this).apply {
                text = cmd
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setBackgroundResource(R.drawable.bg_chip)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(6) }
                setOnClickListener {
                    etTask.setText(cmd)
                    etTask.setSelection(cmd.length)
                    stopListening()
                    hideVoicePanel()
                }
            }
            llVoiceSuggest.addView(chip)
        }
    }

    /** 右下角「切键盘」：结束语音，恢复输入法。 */
    private fun switchToKeyboard() {
        pttActive = false
        pttPendingSend = false
        stopListening()
        hideVoicePanel()
        etTask.requestFocus()
        etTask.setSelection(etTask.length())
        runCatching {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(etTask, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private val speechListener = object : SpeechEngine.Listener {
        override fun onPartial(text: String) = runOnUiThread {
            if (text.isNotBlank()) tvVoicePreview.text = text
            updateMicText(text)
        }

        override fun onResult(text: String) = runOnUiThread {
            updateMicText(text)
            hideVoicePanel()
            // 按住说话：松手后引擎回调最终结果，这时自动发送。
            if (pttPendingSend) {
                pttPendingSend = false
                etTask.post { sendPttIfAny() }
            }
        }

        override fun onError(message: String) {
            if (message.isBlank()) return
            runOnUiThread {
                if (!destroyed) Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
            }
        }

        override fun onStateChanged(listening: Boolean) = runOnUiThread {
            if (destroyed) return@runOnUiThread
            micListening = listening
            if (!listening) {
                micBase = ""
                hideVoicePanel()
            }
        }
    }

    /** 松手提交：输入框有内容才发送，避免空语音把空任务发出去。 */
    private fun sendPttIfAny() {
        if (destroyed) return
        if (etTask.text.isNullOrBlank()) return
        sendTask()
    }

    private fun updateMicText(text: String) {
        if (text.isBlank()) return
        val joined = if (micBase.isBlank()) text else micBase + text
        etTask.setText(joined)
        etTask.setSelection(etTask.length())
    }

    // ==================== 事件处理 ====================

    private fun handleEvent(event: AgentEvent) {
        if (destroyed) return
        // 单条事件渲染失败（如畸形 markdown/公式）不应让整个应用闪退。
        runCatching { dispatchEvent(event) }
            .onFailure {
                CrashLog.e(TAG, "渲染事件失败: $event", it)
                runCatching { removeTyping() }
            }
    }

    private fun dispatchEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.Thinking -> showTyping()
            is AgentEvent.AssistantText -> {
                removeTyping()
                addAssistantMessage(event.text)
                // 悬浮气泡同步展示 AI 的文字输出（剔除代码行），供用户在其他应用中也能看到进度。
                AzAccessibilityService.setBubble(AiOutput.textOnly(event.text))
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
            is AgentEvent.Documents -> {
                removeTyping()
                addDocument(event.path, event.name, event.pages, persist = true)
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
        // 用户输入按纯文本处理，只把 `$...$` 公式渲染出来，避免 `#`、`-` 被当成 markdown 结构。
        Markdown.renderInline(v.findViewById(R.id.tvMsg), text)
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

    /** 渲染 GenOffice 生成的文档卡片：文件名、预览图与打开/分享入口。 */
    private fun addDocument(path: String, name: String, pages: List<String>, persist: Boolean) {
        val v = layoutInflater.inflate(R.layout.item_msg_document, chatContainer, false)
        v.findViewById<TextView>(R.id.tvDocName).text = name
        v.findViewById<Button>(R.id.btnDocOpen).setOnClickListener { openDocument(path) }
        v.findViewById<Button>(R.id.btnDocShare).setOnClickListener { shareDocument(path) }
        chatContainer.addView(v)
        if (pages.isNotEmpty()) addImages(pages, persist = false)
        if (persist) {
            appendTurn(ChatTurn(kind = "document", text = name, attachments = listOf(path), images = pages))
        }
        scrollToBottom()
    }

    /** 用系统「打开方式」打开生成的文档；无匹配应用时提示改用分享。 */
    private fun openDocument(path: String) {
        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(this, getString(R.string.doc_missing), Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(fileUri(file), mimeOfDocument(file.name))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(intent, getString(R.string.doc_open))) }
            .onFailure { Toast.makeText(this, getString(R.string.doc_no_viewer), Toast.LENGTH_SHORT).show() }
    }

    /** 通过分享把文档发送到其他应用。 */
    private fun shareDocument(path: String) {
        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(this, getString(R.string.doc_missing), Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeOfDocument(file.name)
            putExtra(Intent.EXTRA_STREAM, fileUri(file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(intent, getString(R.string.doc_share))) }
            .onFailure { Toast.makeText(this, getString(R.string.doc_no_viewer), Toast.LENGTH_SHORT).show() }
    }

    private fun fileUri(file: File): Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)

    private fun mimeOfDocument(name: String): String =
        when (name.substringAfterLast('.', "").lowercase()) {
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "pdf" -> "application/pdf"
            "md" -> "text/markdown"
            "html" -> "text/html"
            else -> "*/*"
        }

    /** 全屏查看图片，并提供保存到相册的入口。 */
    private fun showImageDialog(url: String) {
        if (!canShowUi()) return
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
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    Toast.makeText(
                        this,
                        if (ok) R.string.image_saved else R.string.image_save_failed,
                        Toast.LENGTH_SHORT,
                    ).show()
                    if (ok) runCatching { dialog.dismiss() }
                }
            }.start()
        }
        dialog.show()
    }

    private fun addNotice(text: String, persist: Boolean = true) {
        val v = layoutInflater.inflate(R.layout.item_msg_system, chatContainer, false)
        Markdown.render(v.findViewById(R.id.tvMsg), text)
        chatContainer.addView(v)
        if (persist) appendTurn(ChatTurn(kind = "notice", text = text))
        scrollToBottom()
    }

    private fun addError(text: String, persist: Boolean = true) {
        val v = layoutInflater.inflate(R.layout.item_msg_error, chatContainer, false)
        Markdown.render(v.findViewById(R.id.tvMsg), text)
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
        Markdown.render(card.args, prettyArgs(argsRaw))
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
        Markdown.render(card.result, truncate(prettyResult(output), 3000))
        if (!ok) card.expand()
        if (persist) {
            // 在列表锁内完成「读取最后一条 + 替换」，避免与工作线程保存时产生竞态。
            val updated = synchronized(session.turns) {
                val last = session.turns.lastOrNull()
                if (last != null && last.kind == "tool") {
                    session.turns[session.turns.lastIndex] = last.copy(toolOk = ok, toolResult = output)
                    true
                } else {
                    false
                }
            }
            if (updated) persistSession()
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
        "genoffice_status" -> getString(R.string.tool_name_genoffice_status)
        "genoffice_document" -> getString(R.string.tool_name_genoffice_document)
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

    // ==================== 历史记录（侧边抽屉） ====================

    private fun closeHistory() {
        drawerRoot.closeDrawer(findViewById<View>(R.id.historyPanel))
    }

    private fun newSession() {
        // create 已返回新会话对象，无需再读回文件。
        session = SessionStore.create(this)
        lastLoadedId = session.id
        closeHistory()
        renderSession()
        refreshDrawer()
    }

    /** 重建历史列表：会话按时间分组，当前会话高亮，行尾可删除。 */
    private fun refreshDrawer() {
        // 先立即用缓存渲染，避免主线程等待磁盘；随后后台扫描一次并刷新。
        val cached = SessionStore.cachedSummaries(this)
        renderDrawer(cached)
        if (drawerScanning) return
        drawerScanning = true
        scanExecutor.execute {
            val fresh = runCatching { SessionStore.refreshSummaries(this) }.getOrDefault(cached)
            runOnUiThread {
                drawerScanning = false
                if (!destroyed) renderDrawer(fresh)
            }
        }
    }

    /** 仅把当前会话高亮切到 [id]，不重建整个列表（切换会话时的快路径）。 */
    private fun highlightDrawer(id: String) {
        for (i in 0 until sessionsContainer.childCount) {
            val child = sessionsContainer.getChildAt(i)
            val tag = child.tag as? String ?: continue
            child.setBackgroundResource(
                if (tag == id) R.drawable.bg_nav_item_selected else R.drawable.bg_nav_item,
            )
        }
    }

    private fun renderDrawer(sessions: List<SessionSummary>) {
        val container = sessionsContainer
        container.removeAllViews()
        val currentId = SessionStore.currentId(this)
        tvSessionsEmpty.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
        tvSessionsCount.text = if (sessions.isEmpty()) "" else getString(R.string.sessions_count, sessions.size)

        var lastGroup: String? = null
        sessions.forEach { s ->
            val group = groupLabel(s.updatedAt)
            if (group != lastGroup) {
                lastGroup = group
                container.addView(drawerSection(group))
            }
            container.addView(drawerSessionRow(s, s.id == currentId))
        }
    }

    /** 抽屉打开时让内容自左向右轻推淡入，营造层次；只对可见行生效，避免大量会话时抖动。 */
    private fun animateDrawerContent() {
        if (drawerAnimated) return
        drawerAnimated = true
        svHistory.scrollTo(0, 0)
        val count = sessionsContainer.childCount
        if (count == 0) return
        val offset = dp(18).toFloat()
        for (i in 0 until count) {
            val child = sessionsContainer.getChildAt(i)
            if (!child.isShown) continue
            child.alpha = 0f
            child.translationX = -offset
            child.animate()
                .alpha(1f)
                .translationX(0f)
                .setStartDelay((i * 18L).coerceAtMost(216L))
                .setDuration(180L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun groupLabel(ts: Long): String {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = ts }
        val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
        val dayGap = now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR)
        return when {
            sameYear && dayGap == 0 -> getString(R.string.group_today)
            sameYear && dayGap == 1 -> getString(R.string.group_yesterday)
            sameYear && dayGap in 2..6 -> getString(R.string.group_week)
            else -> getString(R.string.group_earlier)
        }
    }

    private fun drawerSection(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 11f
        letterSpacing = 0.06f
        setTextColor(getColor(R.color.text_caption))
        setPadding(dp(14), dp(14), dp(14), dp(4))
    }

    private fun drawerSessionRow(s: SessionSummary, selected: Boolean): View {
        val v = layoutInflater.inflate(R.layout.item_nav_session, sessionsContainer, false)
        v.tag = s.id
        v.setBackgroundResource(
            if (selected) R.drawable.bg_nav_item_selected else R.drawable.bg_nav_item,
        )
        v.findViewById<TextView>(R.id.tvTitle).text =
            s.title.ifBlank { getString(R.string.session_default_title) }
        v.setOnClickListener {
            if (s.id == lastLoadedId) {
                closeHistory()
                return@setOnClickListener
            }
            SessionStore.setCurrentId(this, s.id)
            // 先关闭抽屉让收回动画流畅，再加载会话，避免动画期间做重活导致掉帧。
            closeHistory()
            highlightDrawer(s.id)
            switchSession(s.id)
        }
        v.findViewById<View>(R.id.btnDelete).setOnClickListener { confirmDeleteSession(s) }
        return v
    }

    /** 切换当前会话：内存命中立即渲染；否则后台读盘后回主线程渲染。 */
    private fun switchSession(id: String) {
        val cached = SessionStore.cachedSession(id)
        if (cached != null) {
            lastLoadedId = cached.id
            session = cached
            renderSession()
            return
        }
        loadExecutor.execute {
            val loaded = runCatching { SessionStore.get(this, id) }.getOrNull()
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                // 加载期间用户可能又切了别的会话，避免覆盖。
                if (SessionStore.currentId(this) != id) return@runOnUiThread
                if (loaded == null) {
                    Toast.makeText(this, R.string.session_load_failed, Toast.LENGTH_SHORT).show()
                    refreshDrawer()
                    return@runOnUiThread
                }
                lastLoadedId = loaded.id
                session = loaded
                renderSession()
            }
        }
    }

    private fun confirmDeleteSession(s: SessionSummary) {
        if (!canShowUi()) return
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_session_title)
            .setMessage(
                getString(
                    R.string.delete_session_msg,
                    s.title.ifBlank { getString(R.string.session_default_title) },
                ),
            )
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                val wasCurrent = SessionStore.currentId(this) == s.id
                SessionStore.delete(this, s.id)
                // 仅当删掉的是当前会话时才需要切换，避免无谓的读取与重渲染。
                if (wasCurrent) {
                    session = SessionStore.current(this)
                    lastLoadedId = session.id
                    renderSession()
                }
                refreshDrawer()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    // ==================== AI 绘画 ====================

    companion object {
        private const val TAG = "MainActivity"
        private const val REQ_NOTIF = 2002
        /** 全屏助理提交识别文本时携带的任务内容。 */
        const val EXTRA_AUTO_TASK = "app.azcode.bridge.extra.AUTO_TASK"
    }
}

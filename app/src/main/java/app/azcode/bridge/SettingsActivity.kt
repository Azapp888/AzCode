package app.azcode.bridge

import android.Manifest
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.app.AlertDialog
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** 设置页：模型管理入口 + 步数/提示词 + 设备能力，主界面只保留聊天。 */
class SettingsActivity : AppCompatActivity() {

    private val TAG = "Settings"
    private var crashDialogShown = false

    private lateinit var etMaxSteps: EditText
    private lateinit var etSystemPrompt: EditText
    private lateinit var swImageWatermark: Switch
    private lateinit var tvStatus: TextView
    private lateinit var tvSkillsEntry: TextView
    private lateinit var tvMemoryEntry: TextView
    private lateinit var tvLogsEntry: TextView
    private lateinit var tvModelEntry: TextView
    private lateinit var tvGithubEntry: TextView
    private lateinit var tvGenofficeEntry: TextView
    private lateinit var tvImeEntry: TextView
    private lateinit var tvPersonalizationEntry: TextView
    private lateinit var tvVersion: TextView
    private lateinit var btnBridge: Button

    private val shizukuPermissionListener =
        object : rikka.shizuku.Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                runOnUiThread {
                    val msg = if (grantResult == PackageManager.PERMISSION_GRANTED)
                        "Shizuku 已授权" else "Shizuku 授权被拒绝"
                    Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_SHORT).show()
                    refreshStatus()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val inflated = runCatching { setContentView(R.layout.activity_settings) }
            .onFailure { reportCrash("加载设置页布局", it) }
            .isSuccess
        if (!inflated) return

        // 设置页承载了较多子系统的状态读取，任一子系统在特定机型上抛异常都不应让整页闪退。
        // 这里统一兜底并把堆栈落盘 + 展示，便于定位问题机型的具体原因。
        runCatching {
            tvModelEntry = findViewById(R.id.tvModelEntry)
            tvGithubEntry = findViewById(R.id.tvGithubEntry)
            tvGenofficeEntry = findViewById(R.id.tvGenofficeEntry)
            etMaxSteps = findViewById(R.id.etMaxSteps)
            etSystemPrompt = findViewById(R.id.etSystemPrompt)
            swImageWatermark = findViewById(R.id.swImageWatermark)
            tvStatus = findViewById(R.id.tvStatus)
            tvSkillsEntry = findViewById(R.id.tvSkillsEntry)
            tvMemoryEntry = findViewById(R.id.tvMemoryEntry)
            tvLogsEntry = findViewById(R.id.tvLogsEntry)
            tvImeEntry = findViewById(R.id.tvImeEntry)
            tvPersonalizationEntry = findViewById(R.id.tvPersonalizationEntry)
            tvVersion = findViewById(R.id.tvVersion)
            btnBridge = findViewById(R.id.btnBridge)

            tvVersion.text = runCatching {
                val info = packageManager.getPackageInfo(packageName, 0)
                getString(
                    R.string.settings_version_value,
                    info.versionName ?: "-",
                    if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode,
                )
            }.getOrDefault("")

            etMaxSteps.setText(AgentConfig.maxSteps(this).toString())
            etSystemPrompt.setText(AgentConfig.systemPrompt(this))
            swImageWatermark.isChecked = AgentConfig.imageWatermark(this)
            swImageWatermark.setOnCheckedChangeListener { _, checked ->
                AgentConfig.setImageWatermark(this, checked)
            }

            findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
            findViewById<View>(R.id.btnSave).setOnClickListener { saveConfig() }
            findViewById<View>(R.id.rowModel).setOnClickListener {
                startActivity(Intent(this, ProvidersActivity::class.java))
            }
            findViewById<View>(R.id.rowGithub).setOnClickListener {
                startActivity(Intent(this, GitHubActivity::class.java))
            }
            findViewById<View>(R.id.rowGenoffice).setOnClickListener {
                startActivity(Intent(this, GenOfficeActivity::class.java))
            }
            findViewById<View>(R.id.rowSkills).setOnClickListener {
                startActivity(Intent(this, SkillsActivity::class.java))
            }
            findViewById<View>(R.id.rowMemory).setOnClickListener {
                startActivity(Intent(this, MemoryActivity::class.java))
            }
            findViewById<View>(R.id.rowLogs).setOnClickListener { showLogs() }
            findViewById<View>(R.id.rowIme).setOnClickListener { enableIme() }
            findViewById<View>(R.id.rowPersonalization).setOnClickListener {
                startActivity(Intent(this, PersonalizationActivity::class.java))
            }
            findViewById<View>(R.id.btnAccessibility).setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            findViewById<View>(R.id.btnShizuku).setOnClickListener { requestShizuku() }
            findViewById<View>(R.id.btnTermux).setOnClickListener { requestTermux() }
            btnBridge.setOnClickListener { toggleBridge() }
            findViewById<View>(R.id.btnMode).setOnClickListener {
                val mode = DeviceControl.cycleMode(this)
                Toast.makeText(this, "命令执行模式：${mode.label}", Toast.LENGTH_SHORT).show()
                refreshStatus()
            }

            runCatching { rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuPermissionListener) }
        }.onFailure { reportCrash("初始化设置页", it) }
    }

    override fun onResume() {
        super.onResume()
        runCatching { refreshStatus() }.onFailure { reportCrash("刷新设置页状态", it) }
    }

    override fun onDestroy() {
        runCatching { rikka.shizuku.Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener) }
        super.onDestroy()
    }

    private fun saveConfig() {
        val steps = etMaxSteps.text.toString().trim().toIntOrNull()?.coerceIn(0, 1000)
            ?: AgentConfig.DEFAULT_MAX_STEPS
        AgentConfig.setMaxSteps(this, steps)
        AgentConfig.setSystemPrompt(this, etSystemPrompt.text.toString().trim())
        etMaxSteps.setText(steps.toString())
        Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show()
        refreshStatus()
    }

    private fun requestShizuku() {
        when {
            DeviceControl.shizukuGranted() ->
                Toast.makeText(this, "Shizuku 已授权", Toast.LENGTH_SHORT).show()
            !DeviceControl.shizukuServerRunning() ->
                Toast.makeText(this, "Shizuku 服务未运行，请先启动 Shizuku", Toast.LENGTH_LONG).show()
            else -> DeviceControl.requestShizukuPermission(1)
        }
    }

    private fun requestTermux() {
        when {
            !TermuxControl.isInstalled(this) -> {
                Toast.makeText(this, "Termux 未安装，正在打开安装页", Toast.LENGTH_SHORT).show()
                runCatching {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://f-droid.org/packages/com.termux/"),
                        )
                    )
                }
            }
            TermuxControl.isPermissionGranted(this) ->
                Toast.makeText(this, R.string.toast_termux_need_allow_external, Toast.LENGTH_LONG).show()
            else -> requestPermissions(arrayOf(TermuxControl.PERMISSION), 200)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 200) {
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            Toast.makeText(
                this,
                if (granted) R.string.toast_termux_granted else R.string.toast_termux_denied,
                Toast.LENGTH_SHORT,
            ).show()
            if (granted) {
                Toast.makeText(this, R.string.toast_termux_need_allow_external, Toast.LENGTH_LONG).show()
            }
            refreshStatus()
        }
    }

    private fun toggleBridge() {
        if (AgentBridge.isRunning) {
            BridgeService.stop(this)
        } else {
            maybeRequestNotificationPermission()
            BridgeService.start(this)
        }
        tvStatus.postDelayed({ refreshStatus() }, 300)
    }

    private fun refreshStatus() {
        // 状态来自 Shizuku/Root/Termux/输入法等多个子系统，任一读取失败都只记录不闪退。
        runCatching {
            val bridge = if (AgentBridge.isRunning) getString(R.string.status_bridge_on, AgentBridge.PORT)
            else getString(R.string.status_bridge_off)
            val shizuku = if (DeviceControl.shizukuUsable()) getString(R.string.status_shizuku_on)
            else getString(R.string.status_shizuku_off)
            val root = if (DeviceControl.rootAvailable()) getString(R.string.status_root_on)
            else getString(R.string.status_root_off)
            val termux = when {
                !TermuxControl.isInstalled(this) -> getString(R.string.status_termux_not_installed)
                !TermuxControl.isPermissionGranted(this) -> getString(R.string.status_termux_no_permission)
                else -> getString(R.string.status_termux_on)
            }
            val mode = "命令执行模式：${DeviceControl.getMode(this).label}（内置命令行始终可用）"

            tvStatus.text = listOf(bridge, shizuku, root, termux, mode).joinToString("\n")
            btnBridge.setText(
                if (AgentBridge.isRunning) R.string.btn_stop_bridge else R.string.btn_start_bridge
            )

            val total = SkillStore.count(this)
            val enabled = SkillStore.enabled(this).size
            tvSkillsEntry.text = getString(R.string.settings_skills_value, enabled, total)

            tvMemoryEntry.text = getString(R.string.settings_memory_value, MemoryStore.count(this))

            val active = AgentConfig.activeProvider(this)
            tvModelEntry.text = active?.model?.takeIf { it.isNotBlank() }
                ?: getString(R.string.settings_value_unset)

            tvGithubEntry.text = if (GitHubConfig.isConfigured(this)) {
                "@" + GitHubConfig.login(this).ifBlank { "?" }
            } else {
                getString(R.string.settings_value_not_connected)
            }

            tvGenofficeEntry.text = if (GenOfficeConfig.isConfigured(this)) {
                GenOfficeConfig.url(this).removePrefix("http://").removePrefix("https://")
            } else {
                getString(R.string.settings_value_not_connected)
            }

            tvLogsEntry.text = getString(R.string.logs_value, CrashLog.files(this).size)

            tvImeEntry.text = app.azcode.bridge.ime.KeyboardController.statusText(this)
            tvPersonalizationEntry.text = getString(
                if (Personalization.hasConsented(this)) R.string.settings_value_on else R.string.settings_value_off,
            )
        }.onFailure { reportCrash("刷新设置页状态", it) }
    }

    /** 启用并切换内置 AzCode 输入法；无法自动完成时打开系统输入法设置。 */
    private fun enableIme() {
        val ready = runCatching { app.azcode.bridge.ime.KeyboardController.enableAndSwitch(this) }
            .onFailure { reportCrash("启用内置输入法", it) }
            .getOrDefault(false)
        Toast.makeText(
            this,
            if (ready) "已启用并切换为 AzCode 输入法" else "请在系统「输入法设置」中启用 AzCode 输入法",
            Toast.LENGTH_LONG,
        ).show()
        runCatching { refreshStatus() }.onFailure { reportCrash("刷新设置页状态", it) }
    }

    /** 记录并展示设置页内的崩溃信息，避免直接闪退且便于用户回传原因。 */
    private fun reportCrash(where: String, t: Throwable) {
        CrashLog.e(TAG, "设置页异常：$where", t)
        if (crashDialogShown || isFinishing || isDestroyed) return
        crashDialogShown = true
        val detail = android.util.Log.getStackTraceString(t).take(4000)
        runCatching {
            AlertDialog.Builder(this)
                .setTitle("设置页发生异常")
                .setMessage("位置：$where\n\n已写入运行日志，可在「运行日志」中查看。\n\n$detail")
                .setPositiveButton("查看日志") { _, _ -> showLogs() }
                .setNegativeButton(R.string.btn_cancel, null)
                .show()
        }
    }

    /** 展示运行日志：可复制全部或清空，便于把闪退信息反馈出来。 */
    private fun showLogs() {
        val content = CrashLog.readAll(this).ifBlank { getString(R.string.logs_empty) }
        val tv = TextView(this).apply {
            text = content
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(28, 28, 28, 28)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this)
            .setTitle(R.string.title_logs)
            .setView(scroll)
            .setPositiveButton(R.string.btn_copy_logs) { _, _ ->
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("AzCode logs", content))
                Toast.makeText(this, R.string.logs_copied, Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(R.string.btn_clear_logs) { _, _ ->
                CrashLog.clear(this)
                refreshStatus()
                Toast.makeText(this, R.string.logs_cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun maybeRequestNotificationPermission() {
        TaskNotifier.ensureChannel(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }
}

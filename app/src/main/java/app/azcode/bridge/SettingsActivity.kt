package app.azcode.bridge

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

/** 设置页：模型管理入口 + 步数/提示词 + 设备能力，主界面只保留聊天。 */
class SettingsActivity : Activity() {

    private lateinit var etMaxSteps: EditText
    private lateinit var etSystemPrompt: EditText
    private lateinit var tvStatus: TextView
    private lateinit var tvSkillsEntry: TextView
    private lateinit var tvMemoryEntry: TextView
    private lateinit var tvModelEntry: TextView
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
        setContentView(R.layout.activity_settings)

        tvModelEntry = findViewById(R.id.tvModelEntry)
        etMaxSteps = findViewById(R.id.etMaxSteps)
        etSystemPrompt = findViewById(R.id.etSystemPrompt)
        tvStatus = findViewById(R.id.tvStatus)
        tvSkillsEntry = findViewById(R.id.tvSkillsEntry)
        tvMemoryEntry = findViewById(R.id.tvMemoryEntry)
        btnBridge = findViewById(R.id.btnBridge)

        etMaxSteps.setText(AgentConfig.maxSteps(this).toString())
        etSystemPrompt.setText(AgentConfig.systemPrompt(this))

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnSave).setOnClickListener { saveConfig() }
        tvModelEntry.setOnClickListener {
            startActivity(Intent(this, ProvidersActivity::class.java))
        }
        tvSkillsEntry.setOnClickListener {
            startActivity(Intent(this, SkillsActivity::class.java))
        }
        tvMemoryEntry.setOnClickListener {
            startActivity(Intent(this, MemoryActivity::class.java))
        }
        findViewById<View>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<View>(R.id.btnShizuku).setOnClickListener { requestShizuku() }
        btnBridge.setOnClickListener { toggleBridge() }
        findViewById<View>(R.id.btnMode).setOnClickListener {
            val mode = DeviceControl.cycleMode(this)
            Toast.makeText(this, "命令执行模式：${mode.label}", Toast.LENGTH_SHORT).show()
            refreshStatus()
        }

        runCatching { rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuPermissionListener) }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
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
        val bridge = if (AgentBridge.isRunning) getString(R.string.status_bridge_on, AgentBridge.PORT)
        else getString(R.string.status_bridge_off)
        val access = if (AzAccessibilityService.isEnabled()) getString(R.string.status_accessibility_on)
        else getString(R.string.status_accessibility_off)
        val shizuku = if (DeviceControl.shizukuUsable()) getString(R.string.status_shizuku_on)
        else getString(R.string.status_shizuku_off)
        val root = if (DeviceControl.rootAvailable()) getString(R.string.status_root_on)
        else getString(R.string.status_root_off)
        val mode = "命令执行模式：${DeviceControl.getMode(this).label}（内置命令行始终可用）"

        tvStatus.text = listOf(bridge, access, shizuku, root, mode).joinToString("\n")
        btnBridge.setText(
            if (AgentBridge.isRunning) R.string.btn_stop_bridge else R.string.btn_start_bridge
        )

        val total = SkillStore.count(this)
        val enabled = SkillStore.enabled(this).size
        tvSkillsEntry.text = getString(R.string.skills_entry, enabled, total)

        tvMemoryEntry.text = getString(R.string.memory_entry, MemoryStore.count(this))

        val active = AgentConfig.activeProvider(this)
        val providerCount = AgentConfig.enabledProviders(this).size
        tvModelEntry.text = if (active != null) {
            getString(
                R.string.model_entry_active,
                active.name,
                active.model.ifBlank { "-" },
                providerCount,
            )
        } else {
            getString(R.string.model_entry_empty)
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }
}

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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var svLog: ScrollView
    private lateinit var etKey: EditText
    private lateinit var etBase: EditText
    private lateinit var etModel: EditText
    private lateinit var etTask: EditText
    private lateinit var btnRun: Button
    private lateinit var btnStop: Button

    @Volatile private var runner: AgentRunner? = null
    @Volatile private var worker: Thread? = null

    private val shizukuPermissionListener = object : rikka.shizuku.Shizuku.OnRequestPermissionResultListener {
        override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
            runOnUiThread {
                val msg = if (grantResult == PackageManager.PERMISSION_GRANTED) "Shizuku 已授权" else "Shizuku 授权被拒绝"
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                refreshStatus()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        svLog = findViewById(R.id.svLog)
        etKey = findViewById(R.id.etKey)
        etBase = findViewById(R.id.etBase)
        etModel = findViewById(R.id.etModel)
        etTask = findViewById(R.id.etTask)
        btnRun = findViewById(R.id.btnRun)
        btnStop = findViewById(R.id.btnStop)

        etKey.setText(AgentConfig.apiKey(this))
        etBase.setText(AgentConfig.baseUrl(this))
        etModel.setText(AgentConfig.model(this))

        runCatching { rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuPermissionListener) }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            saveConfig()
            Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show()
        }

        btnRun.setOnClickListener { runTask() }
        btnStop.setOnClickListener {
            runner?.cancel()
            appendLog("正在停止…")
        }

        findViewById<Button>(R.id.btnBridge).setOnClickListener {
            if (AgentBridge.isRunning) {
                BridgeService.stop(this)
            } else {
                maybeRequestNotificationPermission()
                BridgeService.start(this)
            }
            tvStatus.postDelayed({ refreshStatus() }, 300)
        }

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnMode).setOnClickListener {
            val mode = DeviceControl.cycleMode(this)
            Toast.makeText(this, "模式：$mode", Toast.LENGTH_SHORT).show()
            refreshStatus()
        }

        findViewById<Button>(R.id.btnShizuku).setOnClickListener {
            if (DeviceControl.shizukuGranted()) {
                Toast.makeText(this, "Shizuku 已授权", Toast.LENGTH_SHORT).show()
            } else if (!DeviceControl.shizukuServerRunning()) {
                Toast.makeText(this, "Shizuku 服务未运行，请先启动 Shizuku", Toast.LENGTH_LONG).show()
            } else {
                DeviceControl.requestShizukuPermission(1)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onDestroy() {
        runner?.cancel()
        runCatching { rikka.shizuku.Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener) }
        super.onDestroy()
    }

    private fun runTask() {
        val task = etTask.text.toString().trim()
        if (task.isEmpty()) {
            Toast.makeText(this, "请输入任务", Toast.LENGTH_SHORT).show()
            return
        }
        if (!AzAccessibilityService.isEnabled()) {
            Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_LONG).show()
            return
        }
        saveConfig()
        if (AgentConfig.apiKey(this).isBlank()) {
            Toast.makeText(this, "请填写 API Key", Toast.LENGTH_LONG).show()
            return
        }

        val r = AgentRunner(this) { line -> appendLog(line) }
        runner = r
        btnRun.isEnabled = false
        btnStop.isEnabled = true
        appendLog("=== 任务开始：$task ===")

        worker = Thread({
            try {
                r.run(task)
            } catch (e: Exception) {
                appendLog("任务失败：${e.message}")
            } finally {
                runOnUiThread {
                    appendLog("=== 任务结束 ===")
                    btnRun.isEnabled = true
                    btnStop.isEnabled = false
                    runner = null
                }
            }
        }, "azcode-agent").apply { start() }
    }

    private fun saveConfig() {
        AgentConfig.save(
            this,
            apiKey = etKey.text.toString().trim(),
            baseUrl = etBase.text.toString().trim().ifBlank { AgentConfig.DEFAULT_BASE },
            model = etModel.text.toString().trim().ifBlank { AgentConfig.DEFAULT_MODEL },
            maxSteps = AgentConfig.maxSteps(this),
        )
    }

    private fun refreshStatus() {
        val access = if (AzAccessibilityService.isEnabled()) getString(R.string.status_accessibility_on)
        else getString(R.string.status_accessibility_off)
        val shizuku = if (DeviceControl.shizukuUsable()) getString(R.string.status_shizuku_on)
        else getString(R.string.status_shizuku_off)
        val root = if (DeviceControl.rootAvailable()) getString(R.string.status_root_on)
        else getString(R.string.status_root_off)
        val bridge = if (AgentBridge.isRunning) getString(R.string.status_bridge_on, AgentBridge.PORT)
        else getString(R.string.status_bridge_off)
        val mode = "模式：${DeviceControl.getMode(this).name}"
        tvStatus.text = listOf(bridge, access, shizuku, root, mode).joinToString("  |  ")
    }

    private fun appendLog(line: String) {
        runOnUiThread {
            tvLog.append(line + "\n")
            svLog.post { svLog.fullScroll(View.FOCUS_DOWN) }
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

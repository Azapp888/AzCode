package app.azcode.bridge

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var tvStatus: TextView

    private val shizukuPermissionListener = object : rikka.shizuku.Shizuku.OnRequestPermissionResultListener {
        override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
            runOnUiThread {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this@MainActivity, "Shizuku 已授权", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Shizuku 授权被拒绝", Toast.LENGTH_SHORT).show()
                }
                refreshStatus()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tvStatus = findViewById(R.id.tvStatus)

        runCatching { rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuPermissionListener) }

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

        findViewById<Button>(R.id.btnShizuku).setOnClickListener {
            if (DeviceControl.shizukuGranted()) {
                Toast.makeText(this, "Shizuku 已授权", Toast.LENGTH_SHORT).show()
            } else if (!DeviceControl.shizukuServerRunning()) {
                Toast.makeText(this, "Shizuku 服务未运行，请先启动 Shizuku", Toast.LENGTH_LONG).show()
            } else {
                DeviceControl.requestShizukuPermission(1)
            }
        }

        findViewById<Button>(R.id.btnMode).setOnClickListener {
            val mode = DeviceControl.cycleMode(this)
            Toast.makeText(this, "模式：$mode", Toast.LENGTH_SHORT).show()
            refreshStatus()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onDestroy() {
        runCatching { rikka.shizuku.Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener) }
        super.onDestroy()
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

        tvStatus.text = listOf(bridge, access, shizuku, root, mode).joinToString("\n")
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }
}

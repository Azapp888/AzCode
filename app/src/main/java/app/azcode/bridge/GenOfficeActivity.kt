package app.azcode.bridge

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * GenOffice 接入：开启后让 Agent 把 Markdown/数据生成为真格式 Office 文档。
 * 文档生成由电脑/Termux 上的 `genoffice mcp --http` 服务完成，App 只填写地址与可选令牌，
 * 凭据仅存于应用私有存储。
 */
class GenOfficeActivity : AppCompatActivity() {

    private lateinit var swEnabled: Switch
    private lateinit var etUrl: EditText
    private lateinit var etToken: EditText
    private lateinit var tvStatus: TextView
    private lateinit var btnSave: Button
    private lateinit var btnTest: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_genoffice)

        swEnabled = findViewById(R.id.swGenoffice)
        etUrl = findViewById(R.id.etGenofficeUrl)
        etToken = findViewById(R.id.etGenofficeToken)
        tvStatus = findViewById(R.id.tvGenofficeStatus)
        btnSave = findViewById(R.id.btnGenofficeSave)
        btnTest = findViewById(R.id.btnGenofficeTest)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        swEnabled.isChecked = GenOfficeConfig.enabled(this)
        etUrl.setText(GenOfficeConfig.url(this))
        etToken.setText(GenOfficeConfig.token(this))

        swEnabled.setOnCheckedChangeListener { _, checked ->
            GenOfficeConfig.setEnabled(this, checked)
            refreshStatus()
        }
        btnSave.setOnClickListener { save() }
        btnTest.setOnClickListener { test() }

        refreshStatus()
    }

    private fun save() {
        val url = etUrl.text.toString().trim()
        if (url.isBlank()) {
            Toast.makeText(this, R.string.genoffice_need_url, Toast.LENGTH_SHORT).show()
            return
        }
        GenOfficeConfig.setUrl(this, url)
        GenOfficeConfig.setToken(this, etToken.text.toString())
        Toast.makeText(this, R.string.genoffice_saved, Toast.LENGTH_SHORT).show()
        refreshStatus()
    }

    private fun test() {
        save()
        if (!GenOfficeConfig.enabled(this)) return
        setBusy(true)
        tvStatus.text = getString(R.string.genoffice_status_on, "…")
        Thread({
            val result = runCatching { GenOfficeMcp.status(this) }
                .onFailure { CrashLog.w(TAG, "GenOffice 连接测试失败: ${it.message}", it) }
            runOnUiThread {
                setBusy(false)
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { info ->
                    tvStatus.text = getString(R.string.genoffice_test_ok, info.optInt("toolCount"))
                }.onFailure {
                    tvStatus.text = getString(R.string.genoffice_test_failed, it.message ?: "")
                }
            }
        }, "azcode-genoffice-test").start()
    }

    private fun refreshStatus() {
        if (!GenOfficeConfig.enabled(this)) {
            tvStatus.text = getString(R.string.genoffice_status_off)
            return
        }
        val url = GenOfficeConfig.url(this).ifBlank { "—" }
        tvStatus.text = getString(R.string.genoffice_status_on, url)
    }

    private fun setBusy(busy: Boolean) {
        btnSave.isEnabled = !busy
        btnTest.isEnabled = !busy
    }

    private companion object {
        const val TAG = "GenOfficeActivity"
    }
}

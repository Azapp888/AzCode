package app.azcode.bridge

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

/**
 * 个性化与隐私详情页：完整说明 AzCode 读取了哪些本机数据、用途是什么、数据存放位置与用户权利。
 * 用户可在此同意、重新生成本地快照，或撤销同意并清除本地数据。
 */
class PersonalizationActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvSnapshot: TextView
    private lateinit var btnConsent: Button
    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_personalization)

        tvStatus = findViewById(R.id.tvStatus)
        tvSnapshot = findViewById(R.id.tvSnapshot)
        btnConsent = findViewById(R.id.btnConsent)
        findViewById<TextView>(R.id.tvAccess).text = getString(R.string.personalization_access_value)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        btnConsent.setOnClickListener {
            Personalization.setConsented(this)
            refreshSnapshot(showToast = true)
        }
        findViewById<View>(R.id.btnRefresh).setOnClickListener { refreshSnapshot(showToast = true) }
        findViewById<View>(R.id.btnRevoke).setOnClickListener { confirmRevoke() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        runCatching { io.shutdown() }
        super.onDestroy()
    }

    private fun refresh() {
        val consented = Personalization.hasConsented(this)
        btnConsent.isEnabled = !consented
        btnConsent.alpha = if (consented) 0.5f else 1f

        val appCount = Personalization.installedAppCount(this)
        val status = buildString {
            append(getString(if (consented) R.string.personalization_status_on else R.string.personalization_status_off))
            append('\n')
            append(getString(R.string.personalization_status_apps, appCount))
            if (consented) {
                append('\n')
                append(getString(R.string.personalization_status_at, formatTime(Personalization.consentedAt(this))))
                val snapAt = Personalization.snapshotAt(this)
                append('\n')
                append(getString(R.string.personalization_status_snapshot, if (snapAt > 0) formatTime(snapAt) else getString(R.string.personalization_never)))
            }
        }
        tvStatus.text = status

        val local = MemoryStore.localEntry(this)
        tvSnapshot.text = local?.content
            ?.takeIf { it.isNotBlank() }
            ?: getString(R.string.personalization_snapshot_empty)
    }

    private fun refreshSnapshot(showToast: Boolean) {
        Toast.makeText(this, R.string.personalization_generating, Toast.LENGTH_SHORT).show()
        io.execute {
            val count = runCatching { Personalization.refreshSnapshot(this) }.getOrDefault(0)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                refresh()
                if (showToast) {
                    Toast.makeText(this, getString(R.string.personalization_generated, count), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun confirmRevoke() {
        AlertDialog.Builder(this)
            .setTitle(R.string.personalization_btn_revoke)
            .setMessage(R.string.personalization_revoke_confirm)
            .setPositiveButton(R.string.btn_confirm) { _, _ ->
                Personalization.revoke(this)
                refresh()
                Toast.makeText(this, R.string.personalization_revoked, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun formatTime(ms: Long): String =
        if (ms <= 0) getString(R.string.personalization_never)
        else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(java.util.Date(ms))

    companion object {
        /** 首启授权说明弹窗：一句话概述 + 查看详情 + 同意。 */
        fun showConsentDialog(context: Context, onAgreed: () -> Unit = {}) {
            AlertDialog.Builder(context)
                .setTitle(R.string.personalization_dialog_title)
                .setMessage(R.string.personalization_dialog_msg)
                .setPositiveButton(R.string.personalization_dialog_agree) { _, _ ->
                    Personalization.setConsented(context)
                    onAgreed()
                    runCatching { context.startActivity(Intent(context, PersonalizationActivity::class.java)) }
                }
                .setNeutralButton(R.string.personalization_dialog_detail) { _, _ ->
                    runCatching { context.startActivity(Intent(context, PersonalizationActivity::class.java)) }
                }
                .setNegativeButton(R.string.personalization_dialog_later, null)
                .show()
        }
    }
}

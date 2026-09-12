package app.azcode.bridge

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * 模型管理：列出全部提供商账号，可增删、启停，并标记当前使用的账号。
 * 点击卡片或「编辑」进入详情页；「导入」可从 Markdown 文档批量录入。
 */
class ProvidersActivity : Activity() {

    private lateinit var container: LinearLayout
    private lateinit var tvEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_providers)

        container = findViewById(R.id.providerContainer)
        tvEmpty = findViewById(R.id.tvEmpty)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnAdd).setOnClickListener { showAddDialog() }
        findViewById<View>(R.id.btnImport).setOnClickListener { pickMarkdown() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        container.removeAllViews()
        val list = AgentConfig.providers(this)
        tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        val activeId = AgentConfig.activeProvider(this)?.id
        list.forEach { container.addView(buildRow(it, activeId)) }
    }

    private fun buildRow(account: ProviderAccount, activeId: String?): View {
        val v = layoutInflater.inflate(R.layout.item_provider, container, false)
        v.findViewById<TextView>(R.id.tvName).text =
            account.name.ifBlank { getString(R.string.provider_unnamed) }
        v.findViewById<TextView>(R.id.tvActive).visibility =
            if (account.id == activeId) View.VISIBLE else View.GONE
        v.findViewById<TextView>(R.id.tvSummary).text = summary(account)

        val sw = v.findViewById<Switch>(R.id.swEnabled)
        sw.setOnCheckedChangeListener(null)
        sw.isChecked = account.enabled
        sw.setOnCheckedChangeListener { _, checked ->
            AgentConfig.setProviderEnabled(this, account.id, checked)
            if (checked && AgentConfig.activeProvider(this) == null) {
                AgentConfig.setActiveId(this, account.id)
            }
            refresh()
        }

        v.setOnClickListener { openEditor(account.id) }
        v.findViewById<View>(R.id.btnEdit).setOnClickListener { openEditor(account.id) }
        v.findViewById<View>(R.id.btnDelete).setOnClickListener { confirmDelete(account) }
        return v
    }

    private fun summary(account: ProviderAccount): String {
        val lines = mutableListOf(
            getString(R.string.provider_summary_protocol, account.protocol.label),
            getString(R.string.provider_summary_model, account.model.ifBlank { "-" }),
        )
        lines.add(
            if (account.hasImage) getString(R.string.provider_summary_image, account.imageModel)
            else getString(R.string.provider_summary_image_off)
        )
        if (account.apiKey.isBlank()) lines.add(getString(R.string.provider_summary_no_key))
        return lines.joinToString("\n")
    }

    private fun openEditor(id: String) {
        startActivity(
            Intent(this, ProviderEditActivity::class.java)
                .putExtra(ProviderEditActivity.EXTRA_ID, id)
        )
    }

    private fun showAddDialog() {
        val names = ModelProviders.TEMPLATES.map { it.name } +
            getString(R.string.provider_custom)
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_add_provider_title)
            .setItems(names.toTypedArray()) { _, which ->
                val templateId = if (which < ModelProviders.TEMPLATES.size) {
                    ModelProviders.TEMPLATES[which].id
                } else {
                    ModelProviders.CUSTOM_ID
                }
                startActivity(
                    Intent(this, ProviderEditActivity::class.java)
                        .putExtra(ProviderEditActivity.EXTRA_TEMPLATE, templateId)
                )
            }
            .show()
    }

    private fun confirmDelete(account: ProviderAccount) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_provider_title)
            .setMessage(
                getString(
                    R.string.delete_provider_msg,
                    account.name.ifBlank { getString(R.string.provider_unnamed) }
                )
            )
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                AgentConfig.removeProvider(this, account.id)
                refresh()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    // ==================== 从 Markdown 导入 ====================

    private fun pickMarkdown() {
        if (AgentConfig.activeProvider(this) == null) {
            Toast.makeText(this, R.string.import_need_model, Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("text/markdown", "text/plain", "text/*", "application/octet-stream"),
            )
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_MD)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_MD || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        val content = runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        if (content.isNullOrBlank()) {
            Toast.makeText(this, R.string.import_read_failed, Toast.LENGTH_SHORT).show()
            return
        }
        importMarkdown(content)
    }

    private fun importMarkdown(content: String) {
        val progress = ProgressDialog(this).apply {
            setMessage(getString(R.string.import_parsing))
            setCancelable(false)
            show()
        }
        Thread({
            val result = runCatching { ProviderImporter.parse(this, content) }
            runOnUiThread {
                progress.dismiss()
                result.onSuccess { accounts ->
                    if (accounts.isEmpty()) {
                        Toast.makeText(this, R.string.import_none, Toast.LENGTH_LONG).show()
                    } else {
                        accounts.forEach { AgentConfig.upsertProvider(this, it) }
                        if (AgentConfig.activeId(this).isBlank()) {
                            accounts.firstOrNull()?.let { AgentConfig.setActiveId(this, it.id) }
                        }
                        Toast.makeText(
                            this,
                            getString(R.string.import_success, accounts.size),
                            Toast.LENGTH_LONG,
                        ).show()
                        refresh()
                    }
                }.onFailure {
                    Toast.makeText(
                        this,
                        getString(R.string.import_failed, it.message ?: ""),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }, "azcode-md-import").start()
    }

    companion object {
        private const val REQ_MD = 3101
    }
}

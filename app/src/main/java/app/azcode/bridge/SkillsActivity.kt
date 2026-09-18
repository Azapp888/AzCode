package app.azcode.bridge

import android.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** 技能管理：列表、启停、删除、从 GitHub 安装。 */
class SkillsActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private lateinit var tvEmpty: TextView
    private lateinit var tvInstallStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_skills)

        container = findViewById(R.id.skillsContainer)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvInstallStatus = findViewById(R.id.tvInstallStatus)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnAdd).setOnClickListener { showAddChooser() }
        findViewById<View>(R.id.btnScan).setOnClickListener { showScanDialog() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        container.removeAllViews()
        val skills = SkillStore.all(this)
        tvEmpty.visibility = if (skills.isEmpty()) View.VISIBLE else View.GONE

        skills.forEach { skill ->
            val v = layoutInflater.inflate(R.layout.item_skill, container, false)
            v.findViewById<TextView>(R.id.tvName).text = skill.name
            Markdown.render(
                v.findViewById(R.id.tvDesc),
                skill.description.ifBlank { "（无描述）" },
            )
            v.findViewById<TextView>(R.id.tvSource).text =
                skill.source.ifBlank { getString(R.string.skill_custom_source) }

            val sw = v.findViewById<Switch>(R.id.swEnabled)
            sw.setOnCheckedChangeListener(null)
            sw.isChecked = skill.enabled
            sw.setOnCheckedChangeListener { _, checked ->
                SkillStore.setEnabled(this, skill.id, checked)
            }

            v.findViewById<View>(R.id.btnEdit).setOnClickListener {
                if (skill.id.startsWith("builtin-")) {
                    toast(getString(R.string.warn_builtin_not_editable))
                } else {
                    showSkillEditor(skill)
                }
            }

            v.findViewById<View>(R.id.btnDelete).setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("删除技能")
                    .setMessage("确定删除「${skill.name}」？")
                    .setPositiveButton("删除") { _, _ ->
                        SkillStore.remove(this, skill.id)
                        refresh()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            container.addView(v)
        }
    }

    /** 「+」入口：可选择新建自定义技能，或从 GitHub 安装。 */
    private fun showAddChooser() {
        val options = arrayOf(
            getString(R.string.btn_new_custom_skill),
            getString(R.string.btn_install_from_github),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_add_skill_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showSkillEditor(null)
                    else -> showAddDialog()
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    /**
     * 新建或编辑技能。skill 为 null 时表示新建。
     * 可自定义名称、简介与提示词内容。
     */
    private fun showSkillEditor(skill: Skill?) {
        val content = layoutInflater.inflate(R.layout.dialog_custom_skill, null)
        val etName = content.findViewById<EditText>(R.id.etSkillName)
        val etDesc = content.findViewById<EditText>(R.id.etSkillDesc)
        val etContent = content.findViewById<EditText>(R.id.etSkillContent)

        if (skill != null) {
            etName.setText(skill.name)
            etDesc.setText(skill.description)
            etContent.setText(skill.content)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(
                if (skill == null) {
                    R.string.dialog_custom_skill_title
                } else {
                    R.string.dialog_edit_custom_skill_title
                },
            )
            .setView(content)
            .setPositiveButton(R.string.btn_confirm, null)
            .setNegativeButton(R.string.btn_cancel, null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = etName.text.toString().trim()
            val desc = etDesc.text.toString().trim()
            val body = etContent.text.toString().trim()
            when {
                name.isBlank() -> toast(getString(R.string.warn_skill_name_required))
                body.isBlank() -> toast(getString(R.string.warn_skill_content_required))
                else -> {
                    saveCustomSkill(skill, name, desc, body)
                    dialog.dismiss()
                }
            }
        }
    }

    private fun saveCustomSkill(existing: Skill?, name: String, desc: String, body: String) {
        val id = existing?.id ?: "custom-" + System.currentTimeMillis().toString(36)
        SkillStore.add(
            this,
            Skill(
                id = id,
                name = name,
                description = desc,
                content = body,
                source = existing?.source.orEmpty(),
                enabled = existing?.enabled ?: true,
            ),
        )
        toast(getString(if (existing == null) R.string.skill_created else R.string.skill_updated, name))
        refresh()
    }

    private fun showAddDialog() {
        val input = EditText(this).apply {
            hint = "https://github.com/owner/repo"
            isSingleLine = true
            textSize = 14f
        }
        val wrap = FrameLayout(this).apply {
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 3, pad, 0)
            addView(input, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ))
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_add_skill_title)
            .setMessage(R.string.dialog_add_skill_msg)
            .setView(wrap)
            .setPositiveButton(R.string.btn_install) { _, _ ->
                install(input.text.toString().trim())
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    /** 扫描热门开源仓库中的插件，选一个一键安装。 */
    private fun showScanDialog() {
        val input = EditText(this).apply {
            hint = "关键词，如：代码审查、写测试、ponytail（留空看精选）"
            isSingleLine = true
            textSize = 14f
        }
        val wrap = FrameLayout(this).apply {
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 3, pad, 0)
            addView(input, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ))
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_scan_plugins_title)
            .setMessage(R.string.dialog_scan_plugins_msg)
            .setView(wrap)
            .setPositiveButton(R.string.btn_scan) { _, _ -> scan(input.text.toString().trim()) }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun scan(keyword: String) {
        tvInstallStatus.visibility = View.VISIBLE
        tvInstallStatus.text = getString(R.string.plugin_scanning)
        Thread({
            val plugins = try {
                PluginCatalog.search(keyword)
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread {
                tvInstallStatus.visibility = View.GONE
                if (plugins.isEmpty()) {
                    toast(getString(R.string.plugin_scan_empty))
                    return@runOnUiThread
                }
                val labels = plugins.map { p ->
                    val stars = if (p.stars > 0) "  ★${p.stars}" else ""
                    "${p.name}$stars\n${p.description.take(80)}"
                }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.dialog_scan_results_title, plugins.size))
                    .setItems(labels) { _, which -> install(plugins[which].installUrl) }
                    .setNegativeButton(R.string.btn_cancel, null)
                    .show()
            }
        }, "azcode-plugin-scan").start()
    }

    private fun install(url: String) {
        if (url.isBlank()) {
            toast("请输入 GitHub 链接")
            return
        }
        tvInstallStatus.visibility = View.VISIBLE
        tvInstallStatus.text = getString(R.string.skill_installing)

        Thread({
            try {
                val skill = GitHubSkillFetcher.install(url)
                SkillStore.add(this, skill)
                runOnUiThread {
                    tvInstallStatus.text = getString(R.string.skill_installed, skill.name)
                    refresh()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvInstallStatus.text = getString(R.string.skill_install_failed, e.message ?: "未知错误")
                }
            }
        }, "azcode-skill-install").start()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}

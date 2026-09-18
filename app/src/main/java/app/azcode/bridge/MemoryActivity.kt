package app.azcode.bridge

import android.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * 记忆管理：分条保存用户提供的敏感信息、使用习惯与技能方法。
 * 敏感条目在界面上默认打码，点击内容可临时显示；启用后注入 Agent 系统提示词。
 */
class MemoryActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private lateinit var tvEmpty: TextView
    private val revealed = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_memory)

        container = findViewById(R.id.memoryContainer)
        tvEmpty = findViewById(R.id.tvEmpty)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnAdd).setOnClickListener { showAddDialog() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        container.removeAllViews()
        val all = MemoryStore.all(this)
        tvEmpty.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE

        MemoryCategory.entries.forEach { category ->
            val items = all.filter { it.category == category }
            if (items.isEmpty()) return@forEach

            val header = TextView(this).apply {
                text = category.label
                textSize = 15f
                setTextColor(this@MemoryActivity.getColor(R.color.text_primary))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                val pad = (4 * resources.displayMetrics.density).toInt()
                setPadding(0, pad * 3, 0, pad * 2)
            }
            container.addView(header)

            items.forEach { entry -> container.addView(buildRow(entry)) }
        }
    }

    private fun buildRow(entry: MemoryEntry): View {
        val v = layoutInflater.inflate(R.layout.item_memory, container, false)
        v.findViewById<TextView>(R.id.tvTitle).text =
            entry.title.ifBlank { "（未命名）" }

        val tvContent = v.findViewById<TextView>(R.id.tvContent)
        val isRevealed = !entry.sensitive || revealed.contains(entry.id)
        val contentText = if (entry.sensitive && !isRevealed) MemoryStore.mask(entry.content) else entry.content
        Markdown.render(tvContent, contentText)
        if (entry.sensitive) {
            tvContent.setOnClickListener {
                if (revealed.contains(entry.id)) revealed.remove(entry.id) else revealed.add(entry.id)
                refresh()
            }
        }

        v.findViewById<TextView>(R.id.tvHint).text = if (entry.sensitive)
            getString(if (isRevealed) R.string.memory_hint_tap_hide else R.string.memory_hint_tap_reveal)
        else
            getString(R.string.memory_hint_normal)

        val sw = v.findViewById<Switch>(R.id.swEnabled)
        sw.setOnCheckedChangeListener(null)
        sw.isChecked = entry.enabled
        sw.setOnCheckedChangeListener { _, checked ->
            MemoryStore.setEnabled(this, entry.id, checked)
        }

        v.findViewById<View>(R.id.btnDelete).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.delete_memory_title)
                .setMessage(getString(R.string.delete_memory_msg, entry.title.ifBlank { "（未命名）" }))
                .setPositiveButton(R.string.btn_delete) { _, _ ->
                    MemoryStore.remove(this, entry.id)
                    revealed.remove(entry.id)
                    refresh()
                }
                .setNegativeButton(R.string.btn_cancel, null)
                .show()
        }
        return v
    }

    private fun showAddDialog() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }

        val label = { text: String ->
            TextView(this).apply {
                this.text = text
                textSize = 13f
                setTextColor(this@MemoryActivity.getColor(R.color.text_secondary))
                setPadding(0, pad / 2, 0, pad / 6)
            }
        }

        val category = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MemoryActivity,
                android.R.layout.simple_spinner_dropdown_item,
                MemoryCategory.entries.map { it.label },
            )
        }
        val etTitle = EditText(this).apply {
            hint = getString(R.string.memory_hint_title)
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 14f
        }
        val etContent = EditText(this).apply {
            hint = getString(R.string.memory_hint_content)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            textSize = 14f
            minLines = 3
        }

        layout.addView(label(getString(R.string.memory_category_label)))
        layout.addView(category)
        layout.addView(label(getString(R.string.memory_title_label)))
        layout.addView(etTitle)
        layout.addView(label(getString(R.string.memory_content_label)))
        layout.addView(etContent)

        val scroll = ScrollView(this).apply { addView(layout) }

        AlertDialog.Builder(this)
            .setTitle(R.string.memory_dialog_title)
            .setView(scroll)
            .setPositiveButton(R.string.btn_save_config) { _, _ ->
                val chosen = MemoryCategory.entries.getOrElse(category.selectedItemPosition) { MemoryCategory.METHOD }
                val title = etTitle.text.toString().trim()
                val content = etContent.text.toString().trim()
                if (content.isEmpty()) {
                    Toast.makeText(this, R.string.memory_content_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                MemoryStore.add(this, chosen, title, content)
                Toast.makeText(this, R.string.memory_saved, Toast.LENGTH_SHORT).show()
                refresh()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }
}

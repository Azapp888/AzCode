package app.azcode.bridge

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * 提供商详情：填写平台名称、API 协议、地址、Key 与语言模型；
 * 打开「加入生图模型」开关后，可再填写该平台的文生图模型。
 */
class ProviderEditActivity : Activity() {

    companion object {
        const val EXTRA_ID = "provider_id"
        const val EXTRA_TEMPLATE = "provider_template"
    }

    private lateinit var spProtocol: Spinner
    private lateinit var etName: EditText
    private lateinit var etBase: EditText
    private lateinit var etApiKey: EditText
    private lateinit var etModels: EditText
    private lateinit var etImageModels: EditText
    private lateinit var swReasoning: Switch
    private lateinit var swImage: Switch
    private lateinit var imageModelBox: View
    private lateinit var tvImageHint: TextView
    private lateinit var tvTitle: TextView
    private lateinit var btnDelete: Button

    private val protocols = ProviderProtocol.entries
    private var updatingProtocol = false
    private var isNew = true
    private lateinit var account: ProviderAccount

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_provider_edit)

        spProtocol = findViewById(R.id.spProtocol)
        etName = findViewById(R.id.etName)
        etBase = findViewById(R.id.etBase)
        etApiKey = findViewById(R.id.etApiKey)
        etModels = findViewById(R.id.etModels)
        etImageModels = findViewById(R.id.etImageModels)
        swReasoning = findViewById(R.id.swReasoning)
        swImage = findViewById(R.id.swImage)
        imageModelBox = findViewById(R.id.imageModelBox)
        tvImageHint = findViewById(R.id.tvImageHint)
        tvTitle = findViewById(R.id.tvTitle)
        btnDelete = findViewById(R.id.btnDelete)

        val editId = intent.getStringExtra(EXTRA_ID)
        val found = editId?.let { id -> AgentConfig.providers(this).firstOrNull { it.id == id } }
        isNew = found == null
        account = found ?: newAccountFromIntent()

        tvTitle.setText(if (isNew) R.string.title_provider_add else R.string.title_provider_edit)
        btnDelete.visibility = if (isNew) View.GONE else View.VISIBLE

        populate()
        setupProtocolSelector()

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnSave).setOnClickListener { save() }
        btnDelete.setOnClickListener { confirmDelete() }
        swImage.setOnCheckedChangeListener { _, _ -> updateImageSection() }
    }

    private fun newAccountFromIntent(): ProviderAccount {
        val tpl = ModelProviders.templateById(intent.getStringExtra(EXTRA_TEMPLATE))
        if (tpl != null) return AgentConfig.accountFromTemplate(tpl)
        return ProviderAccount(
            id = AgentConfig.newAccountId(),
            name = "",
            protocol = ProviderProtocol.OPENAI,
            baseUrl = "",
            apiKey = "",
            enabled = false,
            models = emptyList(),
            model = "",
            supportsReasoningEffort = false,
            imageEnabled = false,
            imageModels = emptyList(),
            imageModel = "",
        )
    }

    private fun populate() {
        etName.setText(account.name)
        etBase.setText(account.baseUrl)
        etApiKey.setText(account.apiKey)
        etModels.setText(account.allModels.joinToString("\n"))
        etImageModels.setText(account.allImageModels.joinToString("\n"))
        swReasoning.isChecked = account.supportsReasoningEffort
        swImage.isChecked = account.imageEnabled
        updateImageSection()
    }

    /** 按行解析多模型输入，去重并保持顺序。 */
    private fun parseLines(text: String): List<String> {
        val out = LinkedHashSet<String>()
        text.split('\n', ',', '，', ';', '；').forEach { line ->
            val s = line.trim()
            if (s.isNotEmpty()) out.add(s)
        }
        return out.toList()
    }

    private fun setupProtocolSelector() {
        updatingProtocol = true
        spProtocol.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            protocols.map { it.label },
        )
        spProtocol.setSelection(protocols.indexOf(account.protocol).coerceAtLeast(0), false)
        updatingProtocol = false
        spProtocol.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (updatingProtocol) return
                updateImageSection()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun selectedProtocol(): ProviderProtocol =
        protocols.getOrNull(spProtocol.selectedItemPosition) ?: ProviderProtocol.OPENAI

    private fun updateImageSection() {
        val protocol = selectedProtocol()
        val supported = protocol.supportsImage
        swImage.isEnabled = supported
        if (!supported && swImage.isChecked) swImage.isChecked = false
        val show = supported && swImage.isChecked
        imageModelBox.visibility = if (show) View.VISIBLE else View.GONE
        tvImageHint.text = getString(
            if (supported) R.string.hint_add_image_desc else R.string.hint_add_image_unsupported
        )
    }

    private fun save() {
        val protocol = selectedProtocol()
        val imageEnabled = protocol.supportsImage && swImage.isChecked
        val models = parseLines(etModels.text.toString())
        val imageModels = if (imageEnabled) parseLines(etImageModels.text.toString()) else emptyList()
        if (models.isEmpty()) {
            Toast.makeText(this, R.string.warn_need_model, Toast.LENGTH_SHORT).show()
            return
        }
        if (imageEnabled && imageModels.isEmpty()) {
            Toast.makeText(this, R.string.warn_need_image_model, Toast.LENGTH_SHORT).show()
            return
        }
        val activeModel = account.model.takeIf { it in models } ?: models.first()
        val activeImageModel = account.imageModel.takeIf { it in imageModels }
            ?: imageModels.firstOrNull().orEmpty()

        val updated = account.copy(
            name = etName.text.toString().trim().ifBlank { protocol.label },
            protocol = protocol,
            baseUrl = etBase.text.toString().trim(),
            apiKey = etApiKey.text.toString().trim(),
            models = models,
            model = activeModel,
            supportsReasoningEffort = swReasoning.isChecked,
            imageEnabled = imageEnabled,
            imageModels = imageModels,
            imageModel = activeImageModel,
            enabled = if (isNew) true else account.enabled,
        )
        AgentConfig.upsertProvider(this, updated)
        if (AgentConfig.activeId(this).isBlank() || AgentConfig.activeProvider(this)?.enabled != true) {
            AgentConfig.setActiveId(this, updated.id)
        }
        Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun confirmDelete() {
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
                finish()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }
}

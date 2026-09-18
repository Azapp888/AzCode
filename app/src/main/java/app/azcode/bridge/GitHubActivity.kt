package app.azcode.bridge

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

/**
 * GitHub 接入：填写 Personal Access Token 与可选默认仓库/分支。
 * Token 属用户自己的凭据，仅存于应用私有存储；保存前先向 GitHub 校验。
 */
class GitHubActivity : AppCompatActivity() {

    private lateinit var etToken: EditText
    private lateinit var etRepo: EditText
    private lateinit var etBranch: EditText
    private lateinit var tvStatus: TextView
    private lateinit var btnSave: Button
    private lateinit var btnVerify: Button
    private lateinit var btnLogout: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_github)

        etToken = findViewById(R.id.etGithubToken)
        etRepo = findViewById(R.id.etGithubRepo)
        etBranch = findViewById(R.id.etGithubBranch)
        tvStatus = findViewById(R.id.tvGithubStatus)
        btnSave = findViewById(R.id.btnGithubSave)
        btnVerify = findViewById(R.id.btnGithubVerify)
        btnLogout = findViewById(R.id.btnGithubLogout)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        etToken.setText(GitHubConfig.token(this))
        etRepo.setText(GitHubConfig.defaultRepo(this))
        etBranch.setText(GitHubConfig.defaultBranch(this))

        btnSave.setOnClickListener { saveAndVerify() }
        btnVerify.setOnClickListener { verify(GitHubConfig.token(this)) }
        btnLogout.setOnClickListener {
            GitHubConfig.clear(this)
            etToken.setText("")
            etRepo.setText("")
            etBranch.setText("")
            refreshStatus()
            Toast.makeText(this, R.string.github_logged_out, Toast.LENGTH_SHORT).show()
        }

        refreshStatus()
    }

    private fun refreshStatus() {
        val token = GitHubConfig.token(this)
        if (token.isBlank()) {
            tvStatus.text = getString(R.string.github_status_off)
            return
        }
        val repo = GitHubConfig.defaultRepo(this).ifBlank { getString(R.string.github_status_none) }
        val branch = GitHubConfig.defaultBranch(this).ifBlank { getString(R.string.github_status_default_branch) }
        tvStatus.text = getString(R.string.github_status_on, "…", repo, branch)
        verify(token)
    }

    private fun saveAndVerify() {
        val token = etToken.text.toString().trim()
        if (token.isBlank()) {
            Toast.makeText(this, R.string.github_need_token, Toast.LENGTH_SHORT).show()
            return
        }
        setBusy(true)
        Thread({
            val result = runCatching {
                val user = GitHubClient.whoami(token)
                GitHubConfig.setToken(this, token)
                GitHubConfig.setDefaultRepo(this, etRepo.text.toString())
                GitHubConfig.setDefaultBranch(this, etBranch.text.toString())
                GitHubConfig.setLogin(this, user.optString("login"))
                user.optString("login")
            }
            runOnUiThread {
                setBusy(false)
                result.onSuccess {
                    Toast.makeText(this, getString(R.string.github_saved, it), Toast.LENGTH_SHORT).show()
                    refreshStatus()
                }.onFailure {
                    Toast.makeText(
                        this,
                        getString(R.string.github_verify_failed, it.message ?: ""),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }, "azcode-github-save").start()
    }

    private fun verify(token: String) {
        if (token.isBlank()) return
        val repo = GitHubConfig.defaultRepo(this).ifBlank { getString(R.string.github_status_none) }
        val branch = GitHubConfig.defaultBranch(this).ifBlank { getString(R.string.github_status_default_branch) }
        tvStatus.text = getString(R.string.github_status_on, "…", repo, branch)
        Thread({
            val result = runCatching { GitHubClient.whoami(token).optString("login") }
                .onFailure { CrashLog.w("GitHubActivity", "校验 Token 失败: ${it.message}", it) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { login ->
                    GitHubConfig.setLogin(this, login)
                    tvStatus.text = getString(R.string.github_status_on, login, repo, branch)
                }.onFailure {
                    tvStatus.text = getString(R.string.github_verify_failed, it.message ?: "")
                }
            }
        }, "azcode-github-verify").start()
    }

    private fun setBusy(busy: Boolean) {
        btnSave.isEnabled = !busy
        btnVerify.isEnabled = !busy
        btnLogout.isEnabled = !busy
        btnSave.text = if (busy) getString(R.string.skill_installing) else getString(R.string.btn_github_save)
    }
}

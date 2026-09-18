package app.azcode.bridge

import android.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** 任务会话管理：新建、切换、删除。每个会话拥有独立的聊天记录与 Agent 上下文。 */
class SessionsActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private lateinit var tvEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sessions)

        container = findViewById(R.id.sessionsContainer)
        tvEmpty = findViewById(R.id.tvEmpty)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnNew).setOnClickListener { createSession() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun createSession() {
        SessionStore.create(this)
        finish()
    }

    private fun refresh() {
        // 先用缓存立即上屏，再在后台扫描磁盘，避免进入页面时卡顿。
        render(SessionStore.cachedSummaries(this))
        scanExecutor.execute {
            val fresh = runCatching { SessionStore.refreshSummaries(this) }.getOrDefault(emptyList())
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                render(fresh)
            }
        }
    }

    private fun render(sessions: List<SessionSummary>) {
        container.removeAllViews()
        val currentId = SessionStore.currentId(this)
        tvEmpty.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE

        sessions.forEach { s ->
            val v = layoutInflater.inflate(R.layout.item_session, container, false)
            v.findViewById<TextView>(R.id.tvTitle).text =
                s.title.ifBlank { getString(R.string.session_default_title) }
            val whenText = DateUtils.getRelativeTimeSpanString(
                s.updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
            ).toString()
            v.findViewById<TextView>(R.id.tvMeta).text =
                getString(R.string.session_meta, s.turnCount, whenText)

            if (s.id == currentId) v.alpha = 1f else v.alpha = 0.72f

            v.setOnClickListener {
                SessionStore.setCurrentId(this, s.id)
                finish()
            }
            v.findViewById<View>(R.id.btnDelete).setOnClickListener {
                confirmDelete(s)
            }
            container.addView(v)
        }
    }

    private fun confirmDelete(s: SessionSummary) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_session_title)
            .setMessage(getString(R.string.delete_session_msg, s.title.ifBlank { getString(R.string.session_default_title) }))
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                SessionStore.delete(this, s.id)
                refresh()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private val scanExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "azcode-sessions").apply { isDaemon = true }
    }

    override fun onDestroy() {
        runCatching { scanExecutor.shutdown() }
        super.onDestroy()
    }
}

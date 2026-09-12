package app.azcode.bridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 聊天记录中的一个「回合」，用于重建界面。
 * kind ∈ user / assistant / notice / error / tool / image
 */
data class ChatTurn(
    val kind: String,
    val text: String = "",
    val attachments: List<String> = emptyList(),
    val toolName: String = "",
    val toolArgs: String = "",
    val toolOk: Boolean = false,
    val toolResult: String = "",
    val images: List<String> = emptyList(),
)

/**
 * 一个「任务」会话：拥有独立标题、聊天记录，以及供模型续接的上下文消息。
 */
data class ChatSession(
    val id: String,
    var title: String,
    var updatedAt: Long,
    val turns: MutableList<ChatTurn> = mutableListOf(),
    var agentMessages: String = "[]",
)

/**
 * 会话持久化：每个会话存为内部存储 files/sessions/<id>.json，当前会话 id 记在 SharedPreferences。
 */
object SessionStore {

    private const val PREFS = "azcode_sessions"
    private const val KEY_CURRENT = "current_id"

    private fun dir(ctx: Context) = File(ctx.filesDir, "sessions").apply { mkdirs() }
    private fun file(ctx: Context, id: String) = File(dir(ctx), "$id.json")
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(ctx: Context): List<ChatSession> =
        dir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.mapNotNull { read(it) }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()

    fun get(ctx: Context, id: String): ChatSession? = read(file(ctx, id))

    fun create(ctx: Context, title: String = "新任务"): ChatSession {
        val now = System.currentTimeMillis()
        val session = ChatSession(id = UUID.randomUUID().toString(), title = title, updatedAt = now)
        save(ctx, session)
        setCurrentId(ctx, session.id)
        return session
    }

    /** 当前会话；没有则新建。 */
    fun current(ctx: Context): ChatSession {
        val id = prefs(ctx).getString(KEY_CURRENT, null)
        if (id != null) get(ctx, id)?.let { return it }
        return list(ctx).firstOrNull() ?: create(ctx)
    }

    fun setCurrentId(ctx: Context, id: String) {
        prefs(ctx).edit().putString(KEY_CURRENT, id).apply()
    }

    fun delete(ctx: Context, id: String) {
        file(ctx, id).delete()
        if (prefs(ctx).getString(KEY_CURRENT, null) == id) {
            prefs(ctx).edit().remove(KEY_CURRENT).apply()
        }
    }

    fun save(ctx: Context, session: ChatSession) {
        session.updatedAt = System.currentTimeMillis()
        val turns = JSONArray()
        session.turns.forEach { t ->
            turns.put(JSONObject().apply {
                put("kind", t.kind)
                put("text", t.text)
                put("attachments", JSONArray(t.attachments))
                put("toolName", t.toolName)
                put("toolArgs", t.toolArgs)
                put("toolOk", t.toolOk)
                put("toolResult", t.toolResult)
                put("images", JSONArray(t.images))
            })
        }
        val obj = JSONObject().apply {
            put("id", session.id)
            put("title", session.title)
            put("updatedAt", session.updatedAt)
            put("turns", turns)
            put("agentMessages", session.agentMessages)
        }
        file(ctx, session.id).writeText(obj.toString())
    }

    private fun read(f: File): ChatSession? = runCatching {
        val obj = JSONObject(f.readText())
        val turnsArr = obj.optJSONArray("turns") ?: JSONArray()
        val turns = (0 until turnsArr.length()).map { i ->
            val o = turnsArr.getJSONObject(i)
            val atts = o.optJSONArray("attachments")
            val imgs = o.optJSONArray("images")
            ChatTurn(
                kind = o.optString("kind"),
                text = o.optString("text"),
                attachments = if (atts == null) emptyList()
                else (0 until atts.length()).map { atts.getString(it) },
                toolName = o.optString("toolName"),
                toolArgs = o.optString("toolArgs"),
                toolOk = o.optBoolean("toolOk"),
                toolResult = o.optString("toolResult"),
                images = if (imgs == null) emptyList()
                else (0 until imgs.length()).map { imgs.getString(it) },
            )
        }.toMutableList()
        ChatSession(
            id = obj.getString("id"),
            title = obj.optString("title", "新任务"),
            updatedAt = obj.optLong("updatedAt"),
            turns = turns,
            agentMessages = obj.optString("agentMessages", "[]"),
        )
    }.getOrNull()

    /** 由首条用户输入生成简短标题。 */
    fun deriveTitle(text: String): String {
        val t = text.trim().replace("\n", " ")
        if (t.isEmpty()) return "新任务"
        return if (t.length <= 20) t else t.take(20) + "…"
    }
}

package app.azcode.bridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Collections
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
 *
 * [turns] 会被 UI 线程（追加消息）与工作线程（任务结束时保存）同时访问，
 * 因此使用同步列表，避免 `ConcurrentModificationException` 导致闪退。
 */
data class ChatSession(
    val id: String,
    var title: String,
    var updatedAt: Long,
    val turns: MutableList<ChatTurn> = Collections.synchronizedList(mutableListOf()),
    @Volatile var agentMessages: String = "[]",
)

/**
 * 会话摘要：历史列表只需要标题与时间，无需解析整个会话。
 * 单独读取可避免切换会话时在文件系统上做大量 JSON 解析。
 */
data class SessionSummary(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val turnCount: Int = 0,
)

/**
 * 会话持久化：每个会话存为内部存储 files/sessions/<id>.json，当前会话 id 记在 SharedPreferences。
 *
 * 历史列表走内存缓存 [summaries]：切换会话时不再逐个读取解析全部文件，
 * 只有缓存失效或磁盘变化时才在后台重新扫描。
 */
object SessionStore {

    private const val PREFS = "azcode_sessions"
    private const val KEY_CURRENT = "current_id"

    /** 保护临时文件写入与重命名，避免同一会话被并发写。 */
    private val WRITE_LOCK = Any()

    /** 会话摘要缓存，volatile 保证跨线程可见。 */
    @Volatile
    private var summaries: List<SessionSummary>? = null

    /** 最近访问的会话对象缓存（LRU）：切换会话时避免重复读盘解析 JSON。 */
    private const val CACHE_LIMIT = 6

    private val sessionCache = object : LinkedHashMap<String, ChatSession>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ChatSession>): Boolean =
            size > CACHE_LIMIT
    }

    private fun dir(ctx: Context) = File(ctx.filesDir, "sessions").apply { mkdirs() }
    private fun file(ctx: Context, id: String) = File(dir(ctx), "$id.json")
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(ctx: Context): List<ChatSession> =
        dir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.mapNotNull { read(it) }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()

    /**
     * 历史列表数据：优先返回缓存；缓存为空时同步扫描一次（仅首次）。
     * 刷新请调用 [refreshSummaries]（应在后台线程执行）。
     */
    fun cachedSummaries(ctx: Context): List<SessionSummary> {
        summaries?.let { return it }
        val loaded = scanSummaries(ctx)
        summaries = loaded
        return loaded
    }

    /** 重新扫描磁盘并更新缓存，返回最新摘要列表。耗时操作，应在后台线程调用。 */
    fun refreshSummaries(ctx: Context): List<SessionSummary> {
        val loaded = scanSummaries(ctx)
        summaries = loaded
        return loaded
    }

    /** 仅解析标题与时间，跳过 turns 大数组，读取开销远小于 [read]。 */
    private fun scanSummaries(ctx: Context): List<SessionSummary> =
        dir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.mapNotNull { f ->
                runCatching {
                    val obj = JSONObject(f.readText())
                    SessionSummary(
                        id = obj.getString("id"),
                        title = obj.optString("title", "新任务"),
                        updatedAt = obj.optLong("updatedAt"),
                        turnCount = obj.optJSONArray("turns")?.length() ?: 0,
                    )
                }.getOrNull()
            }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()

    fun get(ctx: Context, id: String): ChatSession? {
        synchronized(sessionCache) { sessionCache[id] }?.let { return it }
        val loaded = read(file(ctx, id)) ?: return null
        synchronized(sessionCache) { sessionCache[id] = loaded }
        return loaded
    }

    /** 仅当会话已在内存缓存中时返回，不触发读盘（供切换会话的快路径使用）。 */
    fun cachedSession(id: String): ChatSession? =
        synchronized(sessionCache) { sessionCache[id] }

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

    /** 当前会话 id，不读取会话文件。 */
    fun currentId(ctx: Context): String? = prefs(ctx).getString(KEY_CURRENT, null)

    fun setCurrentId(ctx: Context, id: String) {
        prefs(ctx).edit().putString(KEY_CURRENT, id).apply()
    }

    fun delete(ctx: Context, id: String) {
        file(ctx, id).delete()
        if (prefs(ctx).getString(KEY_CURRENT, null) == id) {
            prefs(ctx).edit().remove(KEY_CURRENT).apply()
        }
        summaries = summaries?.filterNot { it.id == id }
        synchronized(sessionCache) { sessionCache.remove(id) }
    }

    /**
     * 原子写盘：先写临时文件再 rename，避免 UI 线程与工作线程同时保存时
     * 产生半个文件导致会话 JSON 损坏、下次读取失败而丢会话。
     *
     * [turns] 遍历时持有列表锁，防止与 UI 线程的追加操作并发。
     */
    fun save(ctx: Context, session: ChatSession) {
        session.updatedAt = System.currentTimeMillis()
        val turns = JSONArray()
        val snapshot = synchronized(session.turns) { session.turns.toList() }
        snapshot.forEach { t ->
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
        val target = file(ctx, session.id)
        val tmp = File(target.parentFile, "${session.id}.tmp")
        synchronized(WRITE_LOCK) {
            tmp.writeText(obj.toString())
            if (!tmp.renameTo(target)) {
                // rename 在个别文件系统上可能失败，退化为直接写。
                target.writeText(obj.toString())
                tmp.delete()
            }
        }
        // 同步维护摘要缓存，历史列表无需重新扫描磁盘即可反映最新标题与时间。
        val entry = SessionSummary(session.id, session.title, session.updatedAt, snapshot.size)
        summaries = (summaries?.filterNot { it.id == session.id } ?: emptyList())
            .plus(entry)
            .sortedByDescending { it.updatedAt }
        synchronized(sessionCache) { sessionCache[session.id] = session }
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
            turns = Collections.synchronizedList(turns),
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

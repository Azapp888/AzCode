package app.azcode.bridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 记忆分类：
 *  - TOKEN  敏感信息（API Token、密码等），分条保存，界面默认打码
 *  - HABIT  用户习惯与偏好
 *  - METHOD 技能与使用方法（用户教过的工具调用方式、命令等）
 *  - LOCAL  本机个性化数据（已安装应用与权限声明）。仅保存在设备本地，
 *            **不会**注入模型上下文，因此不会上传到云端。
 */
enum class MemoryCategory(val key: String, val label: String) {
    TOKEN("token", "敏感信息"),
    HABIT("habit", "使用习惯"),
    METHOD("method", "技能与使用方法"),
    LOCAL("local", "本机个性化（仅本地）");

    companion object {
        fun from(key: String): MemoryCategory =
            entries.firstOrNull { it.key == key } ?: METHOD
    }
}

data class MemoryEntry(
    val id: String,
    val category: MemoryCategory,
    val title: String,
    val content: String,
    val sensitive: Boolean,
    val enabled: Boolean,
    val createdAt: Long,
)

/**
 * 记忆持久化：内部存储 files/memory.json。每条独立保存。
 * 敏感内容仅在界面打码，真实值仍存于应用私有目录。
 */
object MemoryStore {

    private fun file(ctx: Context) = File(ctx.filesDir, "memory.json")

    fun all(ctx: Context): List<MemoryEntry> = runCatching {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            MemoryEntry(
                id = o.optString("id"),
                category = MemoryCategory.from(o.optString("category")),
                title = o.optString("title"),
                content = o.optString("content"),
                sensitive = o.optBoolean("sensitive"),
                enabled = o.optBoolean("enabled", true),
                createdAt = o.optLong("createdAt"),
            )
        }
    }.getOrDefault(emptyList())

    fun byCategory(ctx: Context, category: MemoryCategory): List<MemoryEntry> =
        all(ctx).filter { it.category == category }

    fun enabled(ctx: Context): List<MemoryEntry> =
        all(ctx).filter { it.enabled && it.category != MemoryCategory.LOCAL }

    fun count(ctx: Context): Int = all(ctx).size

    private fun write(ctx: Context, entries: List<MemoryEntry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().apply {
                put("id", e.id)
                put("category", e.category.key)
                put("title", e.title)
                put("content", e.content)
                put("sensitive", e.sensitive)
                put("enabled", e.enabled)
                put("createdAt", e.createdAt)
            })
        }
        file(ctx).writeText(arr.toString())
    }

    fun add(ctx: Context, category: MemoryCategory, title: String, content: String): MemoryEntry {
        val entry = MemoryEntry(
            id = UUID.randomUUID().toString(),
            category = category,
            title = title,
            content = content,
            sensitive = category == MemoryCategory.TOKEN,
            enabled = true,
            createdAt = System.currentTimeMillis(),
        )
        write(ctx, all(ctx) + entry)
        return entry
    }

    fun update(ctx: Context, entry: MemoryEntry) {
        write(ctx, all(ctx).map { if (it.id == entry.id) entry else it })
    }

    fun setEnabled(ctx: Context, id: String, enabled: Boolean) {
        write(ctx, all(ctx).map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    fun remove(ctx: Context, id: String) {
        write(ctx, all(ctx).filterNot { it.id == id })
    }

    fun clear(ctx: Context) = write(ctx, emptyList())

    /** 本机个性化数据的唯一一条记忆；不存在则创建，存在则整体覆盖。 */
    fun upsertLocal(ctx: Context, title: String, content: String) {
        val existing = all(ctx).firstOrNull { it.category == MemoryCategory.LOCAL }
        val entry = existing?.copy(title = title, content = content) ?: MemoryEntry(
            id = UUID.randomUUID().toString(),
            category = MemoryCategory.LOCAL,
            title = title,
            content = content,
            sensitive = false,
            enabled = true,
            createdAt = System.currentTimeMillis(),
        )
        if (existing != null) update(ctx, entry) else write(ctx, all(ctx) + entry)
    }

    fun localEntry(ctx: Context): MemoryEntry? = all(ctx).firstOrNull { it.category == MemoryCategory.LOCAL }

    fun clearLocal(ctx: Context) {
        write(ctx, all(ctx).filterNot { it.category == MemoryCategory.LOCAL })
    }

    /**
     * 敏感内容打码：保留首尾少量字符，中间以圆点代替。
     */
    fun mask(content: String): String {
        val s = content.trim()
        if (s.length <= 4) return "•".repeat(s.length.coerceAtLeast(1))
        if (s.length <= 10) return s.take(2) + "•".repeat(s.length - 2)
        return s.take(4) + "•".repeat(minOf(12, s.length - 8)) + s.takeLast(4)
    }
}

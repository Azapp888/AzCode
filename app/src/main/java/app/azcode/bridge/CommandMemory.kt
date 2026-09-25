package app.azcode.bridge

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * 命令记忆：把用户发过的每条任务作为「命令样本」累计频次与最近使用时间，用于在语音输入页
 * 左下角推荐「用户最常用/最近用过的命令」。
 *
 * 数据仅存于本机应用私有 SharedPreferences，不上传服务器；样本为空时回退到内置推荐命令。
 */
object CommandMemory {

    private const val PREFS = "azcode_command_memory"
    private const val KEY_COMMANDS = "commands"
    private const val MAX_KEEP = 60
    private const val MAX_LEN = 80

    private val presets = listOf(
        "帮我总结一下今天的工作",
        "把这段话翻译成英文",
        "截图并分析当前页面",
        "帮我写一份周报"
    )

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 记录一条用户命令（发送任务时调用）。 */
    fun record(ctx: Context, text: String) {
        val key = normalize(text) ?: return
        val p = prefs(ctx)
        val all = read(p)
        val entry = all.optJSONObject(key) ?: JSONObject()
        entry.put("count", entry.optInt("count", 0) + 1)
        entry.put("last", System.currentTimeMillis())
        all.put(key, entry)
        trim(all)
        p.edit().putString(KEY_COMMANDS, all.toString()).apply()
    }

    /** 返回推荐命令：优先高频、其次最近使用，样本不足时用内置命令补齐。 */
    fun frequent(ctx: Context, limit: Int = 4): List<String> {
        val all = read(prefs(ctx))
        val learned = all.keys().asSequence()
            .mapNotNull { key ->
                val e = all.optJSONObject(key) ?: return@mapNotNull null
                Command(key, e.optInt("count", 0), e.optLong("last", 0L))
            }
            .sortedWith(compareByDescending<Command> { it.count }.thenByDescending { it.last })
            .map { it.text }
            .toMutableList()
        if (learned.size < limit) {
            presets.forEach { p ->
                if (learned.size < limit && learned.none { it == p }) learned.add(p)
            }
        }
        return learned.distinct().take(limit)
    }

    private fun normalize(text: String): String? {
        val t = text.trim().replace(Regex("\\s+"), " ")
        if (t.length < 2) return null
        return if (t.length <= MAX_LEN) t else t.substring(0, MAX_LEN)
    }

    private fun read(p: SharedPreferences): JSONObject =
        runCatching { JSONObject(p.getString(KEY_COMMANDS, "{}") ?: "{}") }
            .getOrDefault(JSONObject())

    private fun trim(all: JSONObject) {
        if (all.length() <= MAX_KEEP) return
        all.keys().asSequence().toList()
            .sortedByDescending { all.optJSONObject(it)?.optLong("last", 0L) ?: 0L }
            .drop(MAX_KEEP)
            .forEach { all.remove(it) }
    }

    private data class Command(val text: String, val count: Int, val last: Long)
}
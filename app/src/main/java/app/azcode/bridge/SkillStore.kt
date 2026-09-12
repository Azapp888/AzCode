package app.azcode.bridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一个「技能」：一段可注入系统提示词的指令文本。
 * 来源可以是内置、或从 GitHub 安装的 SKILL.md。
 */
data class Skill(
    val id: String,
    val name: String,
    val description: String,
    val content: String,
    val source: String,
    val enabled: Boolean,
)

/**
 * 技能持久化：以 JSON 数组存于应用私有 SharedPreferences。
 */
object SkillStore {

    private const val PREFS = "azcode_skills"
    private const val KEY_SKILLS = "skills"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Skill> {
        val raw = prefs(ctx).getString(KEY_SKILLS, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Skill(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    description = o.optString("description"),
                    content = o.optString("content"),
                    source = o.optString("source"),
                    enabled = o.optBoolean("enabled", true),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun enabled(ctx: Context): List<Skill> = all(ctx).filter { it.enabled }

    fun count(ctx: Context): Int = all(ctx).size

    private fun write(ctx: Context, skills: List<Skill>) {
        val arr = JSONArray()
        skills.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                put("description", s.description)
                put("content", s.content)
                put("source", s.source)
                put("enabled", s.enabled)
            })
        }
        prefs(ctx).edit().putString(KEY_SKILLS, arr.toString()).apply()
    }

    /** 新增或按名称/来源覆盖同源技能，返回技能总数。 */
    fun add(ctx: Context, skill: Skill): Int {
        val list = all(ctx).toMutableList()
        val idx = list.indexOfFirst { it.id == skill.id }
        if (idx >= 0) list[idx] = skill else list.add(skill)
        write(ctx, list)
        return list.size
    }

    fun setEnabled(ctx: Context, id: String, enabled: Boolean) {
        write(ctx, all(ctx).map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    fun remove(ctx: Context, id: String) {
        write(ctx, all(ctx).filterNot { it.id == id })
    }

    fun clear(ctx: Context) = write(ctx, emptyList())
}

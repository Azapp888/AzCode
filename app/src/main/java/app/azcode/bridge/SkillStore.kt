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

    /** 内置 ponytail 技能 id。 */
    const val PONYTAIL_ID = "builtin-ponytail"

    /**
     * 内置 ponytail：移植自 ponytail-lite（一条 AGENTS.md 阻止 Agent 过度设计）。
     * 核心是「最小改动原则」，无需外部依赖，随应用内置、可自由启停。
     */
    val PONYTAIL_CONTENT = """
# ponytail · 拒绝过度设计

你是一个克制的工程师。每次动手前，先确认「解决当前问题所需的最小改动」，然后只做这些。

原则：
1. 只实现用户要求的功能，不擅自增加配置项、开关、抽象层或「以后可能用到」的能力。
2. 优先使用已有代码与标准库；新增依赖必须说明为什么现有手段无法完成。
3. 修改范围尽可能小，一次只解决一个问题，不顺手重构无关代码。
4. 能用一个文件解决就不用两个；能不引入接口/工厂/基类就直接写具体实现。
5. 遇到不确定的需求，先用 ask_question_for_user 问清楚，不要凭猜测扩大实现。
6. 完成后用一两句话说明改动，并注明是否引入了新的依赖或文件。

判断口诀：如果这段代码今天没有明确用途，就不要写。
""".trimIndent()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 首次运行时写入内置技能（幂等，不覆盖用户对启停状态的选择）。 */
    fun seedBuiltins(ctx: Context) {
        if (all(ctx).any { it.id == PONYTAIL_ID }) return
        add(
            ctx,
            Skill(
                id = PONYTAIL_ID,
                name = "ponytail · 拒绝过度设计",
                description = "只做最小必要改动，避免多余依赖、抽象与投机功能。",
                content = PONYTAIL_CONTENT,
                source = "",
                enabled = true,
            ),
        )
    }

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

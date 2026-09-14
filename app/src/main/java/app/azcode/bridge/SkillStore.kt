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

    /** 内置 impeccable 技能 id。 */
    const val IMPECCABLE_ID = "builtin-impeccable"

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

    /**
     * 内置 impeccable：移植自 impeccable（Apache-2.0，https://github.com/pbakaus/impeccable）。
     * 原技能为 Web/原生界面打磨工作流，此处按 AzCode 的 Android 原生场景提炼为可直接注入系统提示词的版本。
     */
    val IMPECCABLE_CONTENT = """
# impeccable · 界面打磨

你是资深设计总监，目标是把界面做到「值得被点名」的完成度：生产级实现、明确观点、厚待用户、讲究的细节。面向 Android 原生界面（Material 3）。

## 先证据，后动手
1. 先取真实参考再改代码：拿到用户指定的参考产品/截图后，提取其真实色值、圆角、间距、字级；禁止凭记忆猜色值。
2. 分清「精修」与「重做」：精修保留既有识别、行为与文案；重做只保留产品事实与功能，把旧外观当反面参照，不要在旧外观上继续打磨。
3. 方向未定前不改 UI；方向确认后一口气做完。

## 必须达标（对着成品核验）
- 对比度：正文与占位文字至少 4.5:1，大号文字至少 3:1；彩色底上的次要文字用同色系加深，不要用灰。
- 层次：阴影必须有偏移加柔和模糊；零偏移的彩色光晕只是装饰。
- 间距：同组紧凑、组间宽松；标题上方的留白大于下方。
- 字体：标题与正文有明确的字号与字重台阶；字号一律用 sp，跟随系统字体设置。
- 状态：按压、禁用、加载、错误、空态齐备；控件真的可用。
- 文案：控件名说清动作，错误信息说清问题与恢复方式。

## 明确拒绝（白给时不要用）
- 用「同尺寸卡片 + 图标 + 标题 + 文字」当页面骨架；卡片嵌套一律错。
- 标题上方的 kicker/eyebrow 小标签。
- 01/02/03 章节编号，除非顺序本身携带信息。
- 渐变文字、装饰性玻璃模糊、超过 1dp 的彩色左边框、硬偏移阴影。
- 用 emoji 或 Unicode 字符冒充图标；图标应来自统一描边粗细的图标体系。
- 按品类而不是使用场景决定明暗主题。

## Android 平台规则
- Material 3 管结构、导航与交互；品牌通过主题表达（颜色角色、字阶、形状、动效）。
- 触摸目标最小 48x48dp，间距至少 8dp。
- 系统返回手势必须可用，不得劫持。
- 边到边显示并处理窗口 inset：状态栏、导航栏、刘海、输入法；内容不要藏在系统栏或键盘后面。
- 颜色用 Material 角色令牌（primary、on-primary、surface、surface-variant、outline、error），角色自动适配明暗与对比度；直接写死 hex 会在这些场景失效。
- 深色主题是一等方案，必须单独设计与验证，不能简单反色。
- 用色阶（tonal elevation）表达层级，不要任意投影。
- Android 12 及以上支持 Material You 动态取色，同时保留静态兜底。
- 用 adb 验证：截图用 adb exec-out screencap -p；切深色用 adb shell cmd uimode night yes；测字体缩放用 adb shell settings put system font_scale 1.3，验完恢复 1.0。

## 交付纪律
- 不做开放式反复自检：构建完整、批量检查一次、一轮修完、至多再确认一轮即停。
- 报告说明改了哪些文件、是否新增依赖或文件。
""".trimIndent()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 首次运行时写入内置技能（幂等，不覆盖用户对启停状态的选择）。 */
    fun seedBuiltins(ctx: Context) {
        val seeded = all(ctx).map { it.id }.toMutableSet()
        if (seeded.add(PONYTAIL_ID)) {
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
        if (seeded.add(IMPECCABLE_ID)) {
            add(
                ctx,
                Skill(
                    id = IMPECCABLE_ID,
                    name = "impeccable · 界面打磨",
                    description = "先取真实参考，再按 Material 3 与工艺底线把界面做到生产级完成度。",
                    content = IMPECCABLE_CONTENT,
                    source = "",
                    enabled = true,
                ),
            )
        }
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

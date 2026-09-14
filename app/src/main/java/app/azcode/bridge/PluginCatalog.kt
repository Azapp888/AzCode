package app.azcode.bridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 插件（技能）市场：扫描热门开源仓库中的 Agent 技能，并支持一键安装。
 *
 * 两种来源：
 *  1. 内置精选：离线可用，包含 ponytail（拒绝过度设计）等高质量技能。
 *  2. GitHub 实时搜索：按关键词查询 star 较多的含 SKILL.md 的仓库。
 *
 * 安装复用 [GitHubSkillFetcher]，把目标仓库的 SKILL.md 解析为 [Skill] 存入 [SkillStore]。
 * 所有网络请求都设超时并容错，失败只返回内置结果，不阻断界面。
 */
object PluginCatalog {

    /** 一个可安装的插件候选。 */
    data class Plugin(
        val id: String,
        val name: String,
        val description: String,
        val repo: String,
        val installUrl: String,
        val stars: Int,
        val tags: List<String>,
        val builtin: Boolean,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("name", name)
            put("description", description)
            put("repo", repo)
            put("installUrl", installUrl)
            put("stars", stars)
            put("tags", JSONArray(tags))
            put("builtin", builtin)
        }
    }

    private const val GITHUB_SEARCH = "https://api.github.com/search/repositories"

    /** 内置精选插件：离线可用，始终排在搜索结果前面。 */
    private val CURATED = listOf(
        Plugin(
            id = "ponytail",
            name = "ponytail · 拒绝过度设计",
            description = "让 Agent 只做最小必要改动：不引入多余依赖、不预先抽象、不写投机功能。",
            repo = "ilindaniel/ponytail-lite",
            installUrl = "ilindaniel/ponytail-lite",
            stars = 0,
            tags = listOf("工程", "简洁", "反过度设计"),
            builtin = true,
        ),
    )

    /** 实时搜索：内置精选 + GitHub star 排序结果。keyword 为空时只返回精选。 */
    fun search(keyword: String, limit: Int = 8, token: String = ""): List<Plugin> {
        val kw = keyword.trim()
        val out = LinkedHashSet<String>()
        val result = mutableListOf<Plugin>()

        fun add(p: Plugin) {
            if (out.add(p.installUrl.lowercase())) result.add(p)
        }

        if (kw.isBlank()) {
            CURATED.forEach(::add)
            return result
        }

        val lc = kw.lowercase()
        CURATED.filter {
            it.name.lowercase().contains(lc) ||
                it.description.lowercase().contains(lc) ||
                it.tags.any { t -> t.lowercase().contains(lc) }
        }.forEach(::add)

        githubSearch(kw, limit, token).forEach(::add)
        return result.take(limit.coerceAtLeast(1) + CURATED.size)
    }

    private fun githubSearch(keyword: String, limit: Int, token: String = ""): List<Plugin> {
        return try {
            val q = URLEncoder.encode("$keyword SKILL.md in:name,description,readme", "UTF-8")
            val url = URL("$GITHUB_SEARCH?q=$q&sort=stars&order=desc&per_page=${limit.coerceIn(1, 20)}")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "AzCode-Android")
                if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
            }
            try {
                if (conn.responseCode !in 200..299) return emptyList()
                val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val items = JSONObject(text).optJSONArray("items") ?: return emptyList()
                (0 until items.length()).mapNotNull { i ->
                    val o = items.optJSONObject(i) ?: return@mapNotNull null
                    val full = o.optString("full_name")
                    if (full.isBlank()) return@mapNotNull null
                    Plugin(
                        id = "gh-" + full.replace('/', '-'),
                        name = full,
                        description = o.optString("description").take(160),
                        repo = full,
                        installUrl = full,
                        stars = o.optInt("stargazers_count"),
                        tags = emptyList(),
                        builtin = false,
                    )
                }
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 一键安装：把候选仓库的 SKILL.md 解析并写入技能库，返回技能名。 */
    fun install(ctx: Context, installUrl: String): String {
        val skill = GitHubSkillFetcher.install(installUrl, GitHubConfig.token(ctx))
        SkillStore.add(ctx, skill)
        return skill.name
    }
}

package app.azcode.bridge

import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 从 GitHub 安装技能。
 *
 * 支持的输入形式：
 *  - https://github.com/owner/repo/blob/branch/path/SKILL.md
 *  - https://github.com/owner/repo/tree/branch/subdir    （自动找该目录下 SKILL.md）
 *  - https://github.com/owner/repo                       （自动找默认分支根目录 SKILL.md）
 *  - https://raw.githubusercontent.com/owner/repo/branch/path
 *  - owner/repo 简写
 *
 * 解析 SKILL.md 的 YAML frontmatter（name/description），没有则用首个标题与首行正文。
 */
object GitHubSkillFetcher {

    private const val MAX_BYTES = 512 * 1024

    data class Fetched(val name: String, val description: String, val content: String, val rawUrl: String)

    class FetchException(message: String) : Exception(message)

    fun install(urlInput: String, token: String = ""): Skill {
        val rawUrl = resolveRawUrl(urlInput.trim())
        val body = download(rawUrl, token)
        val parsed = parse(body)
        val id = "gh-" + sha1(rawUrl).take(12)
        return Skill(
            id = id,
            name = parsed.name,
            description = parsed.description,
            content = parsed.content,
            source = rawUrl,
            enabled = true,
        )
    }

    private fun resolveRawUrl(input: String): String {
        if (input.isBlank()) throw FetchException("请输入 GitHub 链接")

        val url = when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            input.matches(Regex("^[\\w.-]+/[\\w.-]+(/.*)?$")) -> "https://github.com/$input"
            else -> throw FetchException("无法识别的链接格式")
        }

        val rawBase = "https://raw.githubusercontent.com/"
        if (url.startsWith(rawBase)) return url

        val ghMatch = Regex("^https?://github\\.com/([^/]+)/([^/]+)(.*)$").find(url)
            ?: throw FetchException("只支持 github.com 或 raw.githubusercontent.com 链接")

        val owner = ghMatch.groupValues[1]
        val repo = ghMatch.groupValues[2].removeSuffix(".git")
        val rest = ghMatch.groupValues[3].removePrefix("/")

        // .../blob/branch/path  → raw
        val blob = Regex("^blob/([^/]+)/(.+)$").find(rest)
        if (blob != null) {
            val branch = blob.groupValues[1]
            val path = blob.groupValues[2]
            return "$rawBase$owner/$repo/$branch/$path"
        }

        // .../tree/branch/subdir → 尝试该目录下 SKILL.md
        val tree = Regex("^tree/([^/]+)(?:/(.*))?$").find(rest)
        if (tree != null) {
            val branch = tree.groupValues[1]
            val sub = tree.groupValues[2].orEmpty().trim('/')
            val path = if (sub.isEmpty()) "SKILL.md" else "$sub/SKILL.md"
            return "$rawBase$owner/$repo/$branch/$path"
        }

        // .../raw/branch/path（GitHub 网页版 raw）
        val raw = Regex("^raw/([^/]+)/(.+)$").find(rest)
        if (raw != null) {
            return "$rawBase$owner/$repo/${raw.groupValues[1]}/${raw.groupValues[2]}"
        }

        // 裸仓库地址 → 默认分支根目录 SKILL.md
        if (rest.isEmpty()) return "$rawBase$owner/$repo/HEAD/SKILL.md"

        throw FetchException("请指向 SKILL.md 文件或仓库目录")
    }

    private fun download(url: String, token: String = ""): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "text/plain, text/markdown, */*")
            setRequestProperty("User-Agent", "AzCode-Android")
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw FetchException(
                    when (code) {
                        404 -> "未找到文件（404）：请确认链接与分支正确，且包含 SKILL.md"
                        403 -> "访问被拒绝（403）：可能触发了 GitHub 频率限制，稍后再试"
                        else -> "下载失败（HTTP $code）"
                    }
                )
            }
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buf = CharArray(8192)
                val sb = StringBuilder()
                var total = 0
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) throw FetchException("技能文件过大（超过 512 KB）")
                    sb.append(buf, 0, n)
                }
                sb.toString()
            }
            if (text.isBlank()) throw FetchException("技能文件内容为空")
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(raw: String): Fetched {
        val text = raw.replace("\r\n", "\n")
        var name = ""
        var description = ""
        var body = text

        val fm = Regex("^---\\n([\\s\\S]*?)\\n---\\n?([\\s\\S]*)$").find(text)
        if (fm != null) {
            val front = fm.groupValues[1]
            body = fm.groupValues[2]
            Regex("(?m)^name:\\s*(.+)$").find(front)?.let { name = it.groupValues[1].trim().trim('"', '\'') }
            Regex("(?m)^description:\\s*(.+)$").find(front)?.let {
                description = it.groupValues[1].trim().trim('"', '\'')
            }
        }

        if (name.isBlank()) {
            Regex("(?m)^#\\s+(.+)$").find(body)?.let { name = it.groupValues[1].trim() }
        }
        if (name.isBlank()) name = "未命名技能"

        if (description.isBlank()) {
            val withoutHeading = body.lines()
                .firstOrNull { it.isNotBlank() && !it.trimStart().startsWith("#") }
                ?.trim()
                .orEmpty()
            description = withoutHeading.take(120)
        }

        val content = body.trim()
        if (content.isBlank()) throw FetchException("技能正文为空")
        return Fetched(name, description, content, "")
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}

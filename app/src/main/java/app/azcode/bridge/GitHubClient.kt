package app.azcode.bridge

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * GitHub 账号与默认仓库配置：属于用户自己的凭据，仅存于应用私有 SharedPreferences。
 * Token 由用户自行填写（在「设置 → GitHub」或让 Agent 代为保存），应用不读取任何环境变量。
 */
object GitHubConfig {

    private const val PREFS = "azcode_github"
    private const val KEY_TOKEN = "token"
    private const val KEY_REPO = "default_repo"
    private const val KEY_BRANCH = "default_branch"
    private const val KEY_LOGIN = "login"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun token(ctx: Context): String = prefs(ctx).getString(KEY_TOKEN, "")!!

    fun setToken(ctx: Context, value: String) {
        prefs(ctx).edit().putString(KEY_TOKEN, value.trim()).apply()
    }

    /** 默认仓库，形如 owner/repo；留空时由工具参数或用户指定。 */
    fun defaultRepo(ctx: Context): String = prefs(ctx).getString(KEY_REPO, "")!!

    fun setDefaultRepo(ctx: Context, value: String) {
        prefs(ctx).edit().putString(KEY_REPO, value.trim()).apply()
    }

    /** 默认分支；留空表示使用仓库默认分支。 */
    fun defaultBranch(ctx: Context): String = prefs(ctx).getString(KEY_BRANCH, "")!!

    fun setDefaultBranch(ctx: Context, value: String) {
        prefs(ctx).edit().putString(KEY_BRANCH, value.trim()).apply()
    }

    /** 校验通过后缓存的登录名，仅用于界面展示。 */
    fun login(ctx: Context): String = prefs(ctx).getString(KEY_LOGIN, "")!!

    fun setLogin(ctx: Context, value: String) {
        prefs(ctx).edit().putString(KEY_LOGIN, value.trim()).apply()
    }

    fun isConfigured(ctx: Context): Boolean = token(ctx).isNotBlank()

    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }
}

/**
 * GitHub REST API 客户端（Contents / Repos / Issues / Pulls / Search）。
 *
 * 只做用户显式请求的仓库读写：读取文件、提交改动、管理 Issue 与 Pull Request。
 * 不包含删除仓库等不可逆操作。所有请求都带超时并在失败时抛出可读错误。
 */
object GitHubClient {

    const val API = "https://api.github.com"
    private const val UA = "AzCode-Android"
    private const val API_VERSION = "2022-11-28"

    class GitHubException(message: String) : Exception(message)

    // ==================== 基础请求 ====================

    private fun request(
        token: String,
        method: String,
        path: String,
        body: JSONObject? = null,
        accept: String = "application/vnd.github+json",
    ): String {
        val conn = (URL(if (path.startsWith("http")) path else API + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", UA)
            setRequestProperty("X-GitHub-Api-Version", API_VERSION)
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) {
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw GitHubException(describeError(code, text))
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun describeError(code: Int, body: String): String {
        val message = runCatching { JSONObject(body).optString("message") }.getOrDefault("")
        return when (code) {
            401 -> "GitHub 未授权（401）：Token 无效或已过期，请重新在「设置 → GitHub」填写。"
            403 -> "GitHub 拒绝访问（403）：${
                message.ifBlank { "Token 权限不足或触发频率限制，请确认已勾选 repo scope。" }
            }"
            404 -> "未找到（404）：仓库、分支或文件不存在，或 Token 无该私有仓库权限。$message"
            409 -> "冲突（409）：文件已被他人修改，请重新读取后再提交。$message"
            422 -> "请求无效（422）：$message"
            else -> "GitHub 请求失败（$code）：$message"
        }
    }

    /** 校验 Token，返回当前登录账号信息。 */
    fun whoami(token: String): JSONObject = JSONObject(request(token, "GET", "/user"))

    fun listRepos(token: String, limit: Int = 30): JSONArray {
        val out = JSONArray()
        var page = 1
        val perPage = limit.coerceIn(1, 100)
        while (out.length() < perPage) {
            val arr = JSONArray(
                request(token, "GET", "/user/repos?per_page=$perPage&page=$page&sort=updated")
            )
            if (arr.length() == 0) break
            for (i in 0 until arr.length()) out.put(arr.getJSONObject(i))
            if (arr.length() < perPage) break
            page++
            if (page > 5) break
        }
        return out
    }

    fun getRepo(token: String, repo: String): JSONObject = JSONObject(request(token, "GET", "/repos/$repo"))

    fun listBranches(token: String, repo: String): JSONArray =
        JSONArray(request(token, "GET", "/repos/$repo/branches?per_page=100"))

    fun listCommits(token: String, repo: String, ref: String = "", limit: Int = 20): JSONArray {
        val q = StringBuilder("/repos/$repo/commits?per_page=${limit.coerceIn(1, 100)}")
        if (ref.isNotBlank()) q.append("&sha=${enc(ref)}")
        return JSONArray(request(token, "GET", q.toString()))
    }

    /** 读取文件内容与当前 sha（用于后续更新）。 */
    fun getFile(token: String, repo: String, path: String, ref: String = ""): JSONObject {
        val q = StringBuilder("/repos/$repo/contents/${encPath(path)}")
        if (ref.isNotBlank()) q.append("?ref=${enc(ref)}")
        return JSONObject(request(token, "GET", q.toString()))
    }

    /**
     * 创建或更新文件并产生一次提交。
     * 更新已有文件时必须提供其 sha；新建文件时 sha 留空。
     */
    fun putFile(
        token: String,
        repo: String,
        path: String,
        content: String,
        message: String,
        branch: String = "",
        sha: String = "",
    ): JSONObject {
        val body = JSONObject()
            .put("message", message.ifBlank { "Update $path" })
            .put("content", Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
        if (branch.isNotBlank()) body.put("branch", branch)
        if (sha.isNotBlank()) body.put("sha", sha)
        return JSONObject(request(token, "PUT", "/repos/$repo/contents/${encPath(path)}", body))
    }

    fun listIssues(token: String, repo: String, state: String = "open", limit: Int = 20): JSONArray {
        val q = "/repos/$repo/issues?state=${enc(state)}&per_page=${limit.coerceIn(1, 100)}"
        return JSONArray(request(token, "GET", q))
    }

    fun createIssue(token: String, repo: String, title: String, body: String = ""): JSONObject {
        val payload = JSONObject().put("title", title)
        if (body.isNotBlank()) payload.put("body", body)
        return JSONObject(request(token, "POST", "/repos/$repo/issues", payload))
    }

    fun commentIssue(token: String, repo: String, number: Int, body: String): JSONObject =
        JSONObject(request(token, "POST", "/repos/$repo/issues/$number/comments", JSONObject().put("body", body)))

    fun listPulls(token: String, repo: String, state: String = "open", limit: Int = 20): JSONArray {
        val q = "/repos/$repo/pulls?state=${enc(state)}&per_page=${limit.coerceIn(1, 100)}"
        return JSONArray(request(token, "GET", q))
    }

    fun createPull(
        token: String,
        repo: String,
        title: String,
        head: String,
        base: String,
        body: String = "",
    ): JSONObject {
        val payload = JSONObject().put("title", title).put("head", head).put("base", base)
        if (body.isNotBlank()) payload.put("body", body)
        return JSONObject(request(token, "POST", "/repos/$repo/pulls", payload))
    }

    fun searchRepos(token: String, query: String, limit: Int = 10): JSONArray {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val res = JSONObject(
            request(token, "GET", "/search/repositories?q=$q&sort=stars&order=desc&per_page=${limit.coerceIn(1, 30)}")
        )
        return res.optJSONArray("items") ?: JSONArray()
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** 逐段编码路径，保留斜杠。 */
    private fun encPath(path: String): String =
        path.trim('/').split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8") }
}

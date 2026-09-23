package app.azcode.bridge

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * GenOffice 桥接配置：用户在 PC/Termux 上运行 `genoffice mcp --http <port>` 后，
 * 在「设置 → GenOffice」填入地址与可选的 Bearer Token。凭据仅存于应用私有
 * SharedPreferences，应用不读取任何环境变量。
 */
object GenOfficeConfig {

    private const val PREFS = "azcode_genoffice"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_URL = "url"
    private const val KEY_TOKEN = "token"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, value).apply()
    }

    /** MCP 服务地址，如 http://192.168.1.5:8765（可带 /mcp 后缀）。 */
    fun url(ctx: Context): String = prefs(ctx).getString(KEY_URL, "")!!

    fun setUrl(ctx: Context, value: String) {
        prefs(ctx).edit().putString(KEY_URL, value.trim()).apply()
    }

    /** 与 `genoffice mcp --http --token <secret>` 对应的访问令牌，可为空。 */
    fun token(ctx: Context): String = prefs(ctx).getString(KEY_TOKEN, "")!!

    fun setToken(ctx: Context, value: String) {
        prefs(ctx).edit().putString(KEY_TOKEN, value.trim()).apply()
    }

    fun isConfigured(ctx: Context): Boolean = enabled(ctx) && url(ctx).isNotBlank()
}

/**
 * GenOffice MCP 客户端（Streamable HTTP / JSON-RPC 2.0）。
 *
 * 设计目标：让 Android 端作为 MCP 客户端，把「生成真格式文档」的算力交给 PC/Termux 上的
 * `genoffice mcp --http` 服务，App 只负责下发 Markdown/数据、取回生成的文件与预览图。
 *
 * 采用「一次高层操作 = 一次 MCP 会话」的短连接模型：初始化握手后依次调用 create_* 与
 * render 工具，结束后关闭会话，无需在 App 侧维护长连接状态。结果文件与预览 PNG 会落盘到
 * 应用私有目录，供聊天界面与服务端渲染共同使用。
 */
object GenOfficeMcp {

    class McpException(message: String) : Exception(message)

    private const val CONNECT_TIMEOUT = 10_000
    private const val READ_TIMEOUT = 180_000
    private const val PROTOCOL_VERSION = "2025-06-18"

    /** 生成结果：本地文件、文件名、格式与预览图路径。 */
    data class Document(
        val path: String,
        val name: String,
        val format: String,
        val pages: List<String>,
        val outputUrl: String,
        val summary: String,
    )

    // ==================== 高层能力 ====================

    /** 探测服务是否可达、可用工具数量。未配置或不可达时抛 [McpException]。 */
    fun status(ctx: Context): JSONObject {
        val base = requireBaseUrl(ctx)
        val token = GenOfficeConfig.token(ctx)
        Session(base, token).use { s ->
            s.initialize()
            val tools = s.listTools()
            return JSONObject()
                .put("ok", true)
                .put("endpoint", base)
                .put("toolCount", tools.size)
                .put("tools", JSONArray(tools))
        }
    }

    /**
     * 依据 [kind] 生成文档：
     *  - docx：内容为 Markdown（以 `<` 开头时按受限 HTML 处理）
     *  - xlsx：内容为 JSON（二维数组或 {"sheets":[{"name","rows"}]}）
     *  - pptx：内容为 deck spec JSON（{"pages":[...]}）
     *
     * @param preview 为 true 时额外调用 render 生成逐页 PNG，供 App 内预览。
     */
    fun createDocument(
        ctx: Context,
        kind: String,
        content: String,
        title: String,
        preview: Boolean = true,
    ): Document {
        if (content.isBlank()) throw McpException("文档内容为空")
        val format = normalizeFormat(kind)
        val base = requireBaseUrl(ctx)
        val token = GenOfficeConfig.token(ctx)

        Session(base, token).use { s ->
            s.initialize()
            val call = when (format) {
                "docx" -> if (content.trimStart().startsWith("<")) {
                    "create_docx" to JSONObject().put("html", content)
                } else {
                    "create_docx" to JSONObject().put("markdown", content)
                }
                "xlsx" -> "create_xlsx" to JSONObject().put("data", content)
                "pptx" -> "create_pptx" to JSONObject().put("spec", content)
                else -> throw McpException("暂不支持的格式：$kind")
            }
            val result = s.callTool(call.first, call.second)
            if (result.isError) throw McpException(result.text.ifBlank { "GenOffice 生成失败" })

            val envelope = runCatching { JSONObject(result.text) }.getOrNull()
                ?: throw McpException("GenOffice 返回了无法解析的结果")
            val outputUrl = envelope.optString("output_url")
            val outputPath = envelope.optString("output_path")

            val fileName = safeFileName(title, outputPath, format)
            val local = File(workDir(ctx), "${System.currentTimeMillis()}-$fileName")
            val bytes = when {
                result.resourceBlob != null -> Base64.decode(result.resourceBlob, Base64.DEFAULT)
                outputUrl.isNotBlank() -> httpGetBytes(outputUrl, token)
                else -> throw McpException("GenOffice 未返回可下载的文件")
            }
            local.writeBytes(bytes)

            val pages = if (preview) renderPreview(ctx, s, outputUrl, token, local.nameWithoutExtension)
            else emptyList()

            return Document(
                path = local.absolutePath,
                name = fileName,
                format = format,
                pages = pages,
                outputUrl = outputUrl,
                summary = envelope.optString("summary", "已生成 $fileName"),
            )
        }
    }

    /** 把服务端渲染的逐页 PNG 下载到本地预览目录；失败时返回空列表，不影响文档生成。 */
    private fun renderPreview(
        ctx: Context,
        session: Session,
        fileUrl: String,
        token: String,
        baseName: String,
    ): List<String> {
        if (fileUrl.isBlank()) return emptyList()
        val result = runCatching { session.callTool("render", JSONObject().put("file", fileUrl)) }
            .getOrNull() ?: return emptyList()
        if (result.isError) return emptyList()
        val dir = File(workDir(ctx), "preview").apply { mkdirs() }
        val out = mutableListOf<String>()

        // 优先使用内联的 image 内容块（≤12 页、≤6MB），否则回退到 detail.files[].url。
        result.images.forEachIndexed { index, png ->
            val f = File(dir, "${baseName}_${index + 1}.png")
            runCatching {
                f.writeBytes(png)
                out.add(f.absolutePath)
            }
        }
        if (out.isEmpty()) {
            runCatching {
                val envelope = JSONObject(result.text)
                val files = envelope.optJSONObject("detail")?.optJSONArray("files") ?: JSONArray()
                for (i in 0 until files.length()) {
                    val url = files.optJSONObject(i)?.optString("url").orEmpty()
                    if (url.isBlank()) continue
                    val f = File(dir, "${baseName}_${i + 1}.png")
                    f.writeBytes(httpGetBytes(url, token))
                    out.add(f.absolutePath)
                }
            }
        }
        return out
    }

    // ==================== MCP 会话 ====================

    private class ToolResult(
        val isError: Boolean,
        val text: String,
        val images: List<ByteArray>,
        val resourceBlob: String?,
    )

    private class Session(baseUrl: String, private val token: String) : java.io.Closeable {
        private val endpoint = "$baseUrl/mcp"
        private var sessionId: String? = null
        private var nextId = 1

        fun initialize() {
            val params = JSONObject()
                .put("protocolVersion", GenOfficeMcp.PROTOCOL_VERSION)
                .put("capabilities", JSONObject())
                .put("clientInfo", JSONObject().put("name", "AzCode-Android").put("version", "1.8.0"))
            val (_, headers) = post(
                JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("id", nextId++)
                    .put("method", "initialize")
                    .put("params", params),
            )
            sessionId = headers["Mcp-Session-Id"] ?: headers["mcp-session-id"]
                ?: throw McpException("服务端未返回会话标识")
            // 握手完成后必须发送 initialized 通知；服务端返回 202 无正文。
            runCatching { notify("notifications/initialized", JSONObject()) }
        }

        fun listTools(): List<String> {
            val result = rpc("tools/list", JSONObject())
            val arr = result.optJSONArray("tools") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name") }
        }

        fun callTool(name: String, args: JSONObject): ToolResult {
            val result = rpc(
                "tools/call",
                JSONObject().put("name", name).put("arguments", args),
            )
            val content = result.optJSONArray("content") ?: JSONArray()
            val text = StringBuilder()
            val images = mutableListOf<ByteArray>()
            var blob: String? = null
            for (i in 0 until content.length()) {
                val block = content.optJSONObject(i) ?: continue
                when (block.optString("type")) {
                    "text" -> text.append(block.optString("text"))
                    "image" -> runCatching {
                        images.add(Base64.decode(block.optString("data"), Base64.DEFAULT))
                    }
                    "resource" -> runCatching {
                        blob = block.optJSONObject("resource")?.optString("blob")
                    }
                }
            }
            return ToolResult(
                isError = result.optBoolean("isError", false),
                text = text.toString(),
                images = images,
                resourceBlob = blob,
            )
        }

        private fun rpc(method: String, params: JSONObject): JSONObject {
            val id = nextId++
            val (_, _, body) = post(
                JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("id", id)
                    .put("method", method)
                    .put("params", params),
            )
            val message = parseResponse(body, id)
            message.optJSONObject("error")?.let {
                throw McpException(it.optString("message", "MCP 调用失败"))
            }
            return message.optJSONObject("result") ?: JSONObject()
        }

        private fun notify(method: String, params: JSONObject) {
            post(
                JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params),
            )
        }

        private fun post(payload: JSONObject): Response {
            val targetId = if (payload.has("id") && !payload.isNull("id")) payload.optInt("id") else null
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = GenOfficeMcp.CONNECT_TIMEOUT
                readTimeout = GenOfficeMcp.READ_TIMEOUT
                doOutput = true
                instanceFollowRedirects = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json, text/event-stream")
                setRequestProperty("User-Agent", "AzCode-Android")
                setRequestProperty("Connection", "close")
                if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
                sessionId?.let { setRequestProperty("Mcp-Session-Id", it) }
            }
            try {
                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val contentType = conn.getHeaderField("Content-Type").orEmpty()
                val headers = HashMap<String, String>()
                conn.headerFields?.forEach { (key, values) ->
                    if (key != null && values.isNotEmpty()) headers[key] = values[0]
                }
                if (code !in 200..299) {
                    val errorBody = conn.errorStream?.bufferedReader(Charsets.UTF_8)
                        ?.use { it.readText() }.orEmpty()
                    throw McpException(GenOfficeMcp.describeError(code, errorBody))
                }
                // SSE 会保持连接不关闭，不能读到流末尾；命中目标 id 即返回。
                val raw = if (contentType.contains("text/event-stream")) {
                    readSse(conn.inputStream, targetId)
                } else {
                    conn.inputStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                }
                return Response(code, headers, raw, contentType)
            } finally {
                conn.disconnect()
            }
        }

        /** 从 SSE 流中读出目标 id 对应的 data 行；[targetId] 为空时取第一段数据。 */
        private fun readSse(input: java.io.InputStream, targetId: Int?): String {
            val reader = input.bufferedReader(Charsets.UTF_8)
            var last = ""
            while (true) {
                val line = reader.readLine() ?: break
                val trimmed = line.trim()
                if (!trimmed.startsWith("data:")) continue
                val data = trimmed.removePrefix("data:").trim()
                if (data.isEmpty() || !data.startsWith("{")) continue
                last = data
                if (targetId == null) break
                val obj = runCatching { JSONObject(data) }.getOrNull() ?: continue
                if (obj.optInt("id", Int.MIN_VALUE) == targetId) return data
            }
            return last
        }

        /** 解析最终响应：readSse 通常已返回命中的 data 行，这里兼容完整 JSON 与残留 SSE 文本。 */
        private fun parseResponse(body: String, id: Int): JSONObject {
            val trimmed = body.trim()
            if (trimmed.isBlank()) return JSONObject()
            if (trimmed.startsWith("{")) {
                return runCatching { JSONObject(trimmed) }.getOrElse {
                    throw McpException("无法解析服务端响应")
                }
            }
            for (line in trimmed.lineSequence()) {
                val data = line.trim().removePrefix("data:").trim()
                if (!data.startsWith("{")) continue
                val obj = runCatching { JSONObject(data) }.getOrNull() ?: continue
                if (obj.optInt("id", Int.MIN_VALUE) == id) return obj
            }
            throw McpException("服务端未返回对应的响应")
        }

        override fun close() {
            val id = sessionId ?: return
            runCatching {
                val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "DELETE"
                    connectTimeout = GenOfficeMcp.CONNECT_TIMEOUT
                    readTimeout = 5_000
                    setRequestProperty("Mcp-Session-Id", id)
                    if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
                }
                conn.responseCode
                conn.disconnect()
            }
        }
    }

    private data class Response(
        val code: Int,
        val headers: Map<String, String>,
        val body: String,
        val contentType: String,
    )

    // ==================== 工具函数 ====================

    private fun requireBaseUrl(ctx: Context): String {
        if (!GenOfficeConfig.enabled(ctx)) {
            throw McpException("GenOffice 未开启，请在「设置 → GenOffice」中开启并填写服务地址")
        }
        val raw = GenOfficeConfig.url(ctx)
        if (raw.isBlank()) {
            throw McpException("未配置 GenOffice 服务地址，请在「设置 → GenOffice」中填写")
        }
        return normalizeBase(raw)
    }

    /** 允许用户只填主机端口或连 /mcp 一起填，统一成不含结尾斜杠、不含 /mcp 的基础地址。 */
    internal fun normalizeBase(raw: String): String {
        var value = raw.trim()
        if (!value.startsWith("http://") && !value.startsWith("https://")) value = "http://$value"
        value = value.trimEnd('/')
        if (value.endsWith("/mcp")) value = value.dropLast(4).trimEnd('/')
        return value
    }

    private fun normalizeFormat(kind: String): String = when (kind.trim().lowercase()) {
        "word", "doc", "docx" -> "docx"
        "excel", "sheet", "xls", "xlsx" -> "xlsx"
        "ppt", "slides", "pptx" -> "pptx"
        else -> kind.trim().lowercase()
    }

    private fun workDir(ctx: Context): File = File(ctx.filesDir, "genoffice").apply { mkdirs() }

    private fun safeFileName(title: String, outputPath: String, format: String): String {
        val fromPath = outputPath.substringAfterLast('/').substringAfterLast('\\')
        val base = when {
            fromPath.isNotBlank() -> fromPath
            title.isNotBlank() -> "${title.trim()}.$format"
            else -> "document.$format"
        }
        return base.replace(Regex("[^\\w.\\- ]"), "_").take(80).ifBlank { "document.$format" }
    }

    private fun httpGetBytes(url: String, token: String): ByteArray {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "AzCode-Android")
            setRequestProperty("Connection", "close")
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw McpException("下载文件失败（$code）")
            val out = ByteArrayOutputStream()
            conn.inputStream.use { input -> input.copyTo(out) }
            return out.toByteArray()
        } finally {
            conn.disconnect()
        }
    }

    private fun describeError(code: Int, body: String): String {
        val message = runCatching { JSONObject(body).optString("error") }.getOrDefault("")
        return when (code) {
            401 -> "GenOffice 拒绝访问（401）：请检查访问令牌是否与 --token 一致。"
            403 -> "GenOffice 拒绝访问（403）：服务端未允许该来源。"
            404 -> "GenOffice 接口不存在（404）：请确认地址与端口，并已运行 `genoffice mcp --http`。"
            else -> "GenOffice 请求失败（$code）：${message.ifBlank { body.take(200) }}"
        }
    }
}

package app.azcode.bridge.llm.core

import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * 统一 HTTP 传输层：仅使用 JDK 标准库，不引入额外依赖。
 *
 * Android 的 HttpURLConnection 默认复用 keep-alive 连接；当对端或中间网关先关闭了空闲连接，
 * 复用旧连接会抛出 `SocketException: Software caused connection abort`。这里显式
 * `Connection: close` 且请求结束即 disconnect，避免复用陈旧连接；同时对瞬时错误自动重试。
 *
 * 网络类失败统一抛出 [LLMException]（NETWORK / TIMEOUT），业务层无需再区分底层异常。
 */
object LLMTransport {

    private const val httpAttempts = 3
    private const val connectTimeoutMs = 15_000

    fun get(
        url: String,
        headers: Map<String, String>,
        readTimeoutMs: Int = 30_000,
    ): Pair<Int, String> = request(url, "GET", headers, null, readTimeoutMs)

    fun post(
        url: String,
        headers: Map<String, String>,
        body: String,
        readTimeoutMs: Int = 120_000,
    ): Pair<Int, String> = request(url, "POST", headers, body, readTimeoutMs)

    private fun request(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
        readTimeoutMs: Int,
    ): Pair<Int, String> {
        var last: Exception? = null
        for (attempt in 0 until httpAttempts) {
            try {
                return requestOnce(url, method, headers, body, readTimeoutMs)
            } catch (e: Exception) {
                if (!isTransient(e) || attempt == httpAttempts - 1) {
                    if (isTransient(e)) throw networkException(e)
                    throw e
                }
                last = e
                Thread.sleep(400L * (attempt + 1))
            }
        }
        throw networkException(last)
    }

    private fun requestOnce(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
        readTimeoutMs: Int,
    ): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Connection", "close")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return code to text
        } finally {
            conn.disconnect()
        }
    }

    private fun isTransient(e: Throwable): Boolean = when (e) {
        is SocketException,
        is SocketTimeoutException,
        is ConnectException,
        is InterruptedIOException,
        -> true

        else -> false
    }

    fun networkException(e: Throwable?): LLMException {
        val raw = e?.message.orEmpty()
        val timeout = e is SocketTimeoutException || raw.contains("timed out", ignoreCase = true)
        val message = when {
            timeout -> "网络请求超时，请检查网络后重试"
            e is UnknownHostException -> "无法解析服务器地址，请检查网络与 Base URL"
            raw.contains("abort", ignoreCase = true) || e is SocketException ->
                "网络连接被中断，已自动重试仍失败，请检查网络或稍后再试"
            e is ConnectException -> "无法连接服务器，请检查网络与 Base URL"
            raw.isNotBlank() -> "网络请求失败：$raw"
            else -> "网络请求失败，请检查网络后重试"
        }
        return LLMException(
            code = if (timeout) LLMErrorCode.TIMEOUT else LLMErrorCode.NETWORK,
            message = message,
            cause = e,
        )
    }
}

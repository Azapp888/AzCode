package app.azcode.bridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.ImageView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 异步加载网络/数据 URI 图片到 ImageView。服务于：
 *  - 文生图工具返回的图片 URL
 *  - AI 回复 markdown 中引用的图片链接
 * 仅使用 JDK 标准库下载，统一限制尺寸以防 OOM。
 */
object ImageLoader {

    private val executor: ExecutorService = Executors.newFixedThreadPool(4)
    private const val MAX_EDGE = 1600
    private const val MAX_BYTES = 8 * 1024 * 1024

    fun load(iv: ImageView, uri: String, fallback: Bitmap? = null) {
        iv.tag = uri
        executor.execute {
            val bmp = try {
                download(uri)
            } catch (e: Exception) {
                null
            }
            iv.post {
                if (iv.tag == uri) {
                    val result = bmp ?: fallback
                    if (result != null) iv.setImageBitmap(result)
                    else iv.setImageDrawable(null)
                }
            }
        }
    }

    /** 同步下载并解码，返回压缩后的 Bitmap（供后台线程调用）。 */
    private fun download(uri: String): Bitmap? {
        val bytes = readBytes(uri) ?: return null
        return decode(bytes)
    }

    private fun readBytes(uri: String): ByteArray? {
        return when {
            uri.startsWith("data:") -> decodeDataUri(uri)
            uri.startsWith("http://") || uri.startsWith("https://") -> httpGet(uri)
            else -> localFile(uri)
        }
    }

    /** 将 data URI 落盘为缓存文件，返回可持久化的路径；远程 URL 原样返回。 */
    fun persist(ctx: Context, uri: String): String {
        if (!uri.startsWith("data:")) return uri
        val bytes = decodeDataUri(uri) ?: return uri
        val dir = File(ctx.cacheDir, "az_images").apply { mkdirs() }
        val name = "img_${System.currentTimeMillis()}_${bytes.size}.png"
        val f = File(dir, name)
        return runCatching {
            f.writeBytes(bytes)
            f.absolutePath
        }.getOrDefault(uri)
    }

    private fun localFile(uri: String): ByteArray? {
        val path = if (uri.startsWith("file://")) uri.substringAfter("file://") else uri
        val f = File(path)
        return if (f.exists()) f.readBytes() else null
    }

    private fun httpGet(uri: String): ByteArray? {
        val conn = URL(uri).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", "AzCode/1.0")
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else null
            stream?.use { s ->
                val limited = LimitedInputStream(s, MAX_BYTES)
                limited.readBytes()
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun decodeDataUri(uri: String): ByteArray? {
        val comma = uri.indexOf(',')
        if (comma < 0) return null
        val meta = uri.substring(0, comma)
        val payload = uri.substring(comma + 1)
        if (!meta.contains("base64")) return null
        return android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / sample > MAX_EDGE || bounds.outHeight / sample > MAX_EDGE) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null

        if (bmp.byteCount <= MAX_BYTES / 2) return bmp

        val out = java.io.ByteArrayOutputStream()
        if (bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)) {
            bmp.recycle()
            val outBytes = out.toByteArray()
            return BitmapFactory.decodeByteArray(outBytes, 0, outBytes.size)
        }
        return bmp
    }

    private class LimitedInputStream(
        private val inner: java.io.InputStream,
        private val limit: Int,
    ) : java.io.InputStream() {
        private var total = 0
        override fun read(): Int {
            if (total >= limit) return -1
            val b = inner.read()
            if (b != -1) total++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (total >= limit) return -1
            val allowed = minOf(len, limit - total)
            val n = inner.read(b, off, allowed)
            if (n > 0) total += n
            return n
        }

        override fun close() = inner.close()
    }
}
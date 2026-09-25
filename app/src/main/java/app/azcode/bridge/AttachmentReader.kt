package app.azcode.bridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * 附件读取与转码。
 *
 * 依据 DeepSeek API 能力分类：
 *  - 图片（JPEG/PNG/GIF/WebP）→ base64 内联，走视觉输入
 *  - 音频（mp3/wav/m4a/aac/ogg/flac/amr/opus）→ base64 内联，供支持音频输入的模型解析
 *  - PDF → 本地用 PdfRenderer 渲染页面为 JPEG，再作为图片发送
 *  - Office（docx/xlsx/pptx）→ 本地解包提取文本后并入文本内容
 *  - 文本/代码（txt/md/csv/json/…）→ 直接读取为文本
 */
object AttachmentReader {

    /** 文件选择器过滤的 MIME 类型。 */
    val PICK_MIME_TYPES = arrayOf(
        "image/jpeg", "image/png", "image/gif", "image/webp",
        "application/pdf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "audio/mpeg", "audio/mp4", "audio/wav", "audio/x-wav", "audio/aac",
        "audio/ogg", "audio/flac", "audio/x-m4a", "audio/3gpp", "audio/amr",
        "text/plain", "text/markdown", "text/csv", "text/tsv",
        "text/html", "text/xml", "text/javascript", "text/x-java", "text/x-kotlin",
        "application/json", "application/xml", "application/javascript",
    )

    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp")
    private val DOC_EXT = setOf("pdf", "docx", "xlsx", "pptx")
    private val AUDIO_EXT = setOf("mp3", "wav", "m4a", "aac", "ogg", "oga", "flac", "amr", "opus", "3gp", "3gpp")
    private val TEXT_EXT = setOf(
        "txt", "md", "markdown", "csv", "tsv", "json", "xml", "html", "htm",
        "yaml", "yml", "log", "ini", "conf", "properties", "toml", "env",
        "kt", "kts", "java", "py", "js", "mjs", "ts", "tsx", "jsx", "c", "h",
        "cpp", "hpp", "cs", "go", "rs", "rb", "php", "sh", "bat", "sql",
        "swift", "dart", "lua", "vue", "gradle", "gitignore",
    )

    private val LEGACY_OFFICE_EXT = setOf("doc", "xls", "ppt")

    private const val MAX_TEXT_CHARS = 200_000
    private const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
    private const val MAX_AUDIO_BYTES = 12 * 1024 * 1024
    private const val MAX_PDF_PAGES = 8
    private const val IMAGE_TARGET_PX = 1400
    private const val MAX_IMAGE_EDGE = 8192

    enum class Kind { IMAGE, AUDIO, DOCUMENT, TEXT }

    data class Pending(val uri: Uri, val name: String, val mime: String, val kind: Kind)

    sealed interface Prepared {
        val name: String
        data class Image(override val name: String, val mime: String, val base64: String) : Prepared
        data class Audio(override val name: String, val mime: String, val format: String, val base64: String) : Prepared
        data class Text(override val name: String, val text: String) : Prepared
    }

    class UnsupportedException(message: String) : Exception(message)

    fun kindOf(name: String, mime: String): Kind {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (mime.startsWith("image/") || ext in IMAGE_EXT) return Kind.IMAGE
        if (mime.startsWith("audio/") || ext in AUDIO_EXT) return Kind.AUDIO
        if (mime == "application/pdf" || ext == "pdf") return Kind.DOCUMENT
        if (ext in setOf("docx", "xlsx", "pptx")) return Kind.DOCUMENT
        if (mime.startsWith("text/") || ext in TEXT_EXT ||
            mime == "application/json" || mime == "application/xml" ||
            mime == "application/javascript"
        ) return Kind.TEXT
        if (ext in LEGACY_OFFICE_EXT) {
            throw UnsupportedException("暂不支持旧版 .$ext，请另存为 .${ext}x 后重试")
        }
        throw UnsupportedException("不支持的文件类型：$name")
    }

    /** 读取并转码为可发送的附件。需在后台线程调用。 */
    fun prepare(ctx: Context, pending: Pending): Prepared {
        val ext = pending.name.substringAfterLast('.', "").lowercase()
        return when (pending.kind) {
            Kind.IMAGE -> prepareImage(ctx, pending)
            Kind.AUDIO -> prepareAudio(ctx, pending)
            Kind.TEXT -> Prepared.Text(pending.name, readText(ctx, pending.uri))
            Kind.DOCUMENT -> when (ext) {
                "pdf" -> throw UnsupportedException("PDF 请通过 prepareAll 渲染")
                "docx" -> Prepared.Text(pending.name, readZipText(ctx, pending.uri) { it == "word/document.xml" })
                "xlsx" -> Prepared.Text(pending.name, readZipText(ctx, pending.uri) {
                    it == "xl/sharedStrings.xml" || it.matches(Regex("xl/worksheets/sheet\\d+\\.xml"))
                })
                "pptx" -> Prepared.Text(pending.name, readZipText(ctx, pending.uri) {
                    it.matches(Regex("ppt/slides/slide\\d+\\.xml"))
                })
                else -> throw UnsupportedException("不支持的文件类型：${pending.name}")
            }
        }
    }

    /** 图片附件：可能为多张（PDF 渲染）或单张。 */
    fun prepareAll(ctx: Context, pending: Pending): List<Prepared> =
        if (pending.kind == Kind.DOCUMENT && pending.name.endsWith(".pdf", true)) {
            val images = renderPdf(ctx, pending.uri)
            if (images.isEmpty()) throw UnsupportedException("PDF 无可渲染页面")
            images.mapIndexed { i, b64 ->
                Prepared.Image("${pending.name}#p${i + 1}", "image/jpeg", b64)
            }
        } else {
            listOf(prepare(ctx, pending))
        }

    // ==================== 图片 ====================

    private fun prepareImage(ctx: Context, pending: Pending): Prepared.Image {
        val raw = ctx.contentResolver.openInputStream(pending.uri)?.use { it.readBytes() }
            ?: throw UnsupportedException("无法读取文件：${pending.name}")

        val mime = detectImageMime(raw) ?: normalizedImageMime(pending.mime, pending.name)
        ?: throw UnsupportedException("无法识别的图片格式（仅支持 JPEG/PNG/GIF/WebP）")

        val bytes = if (raw.size <= MAX_IMAGE_BYTES) raw else compress(raw)
        return Prepared.Image(pending.name, mime, Base64.encodeToString(bytes, Base64.NO_WRAP))
    }

    private fun detectImageMime(b: ByteArray): String? = when {
        b.size < 12 -> null
        b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "image/jpeg"
        b[0] == 0x89.toByte() && b[1] == 0x50.toByte() -> "image/png"
        b[0] == 0x47.toByte() && b[1] == 0x49.toByte() -> "image/gif"
        b[0] == 0x52.toByte() && b[1] == 0x49.toByte() &&
            b[8] == 0x57.toByte() && b[9] == 0x45.toByte() -> "image/webp"
        else -> null
    }

    private fun normalizedImageMime(mime: String, name: String): String? = when {
        mime in setOf("image/jpeg", "image/png", "image/gif", "image/webp") -> mime
        name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
        name.endsWith(".png", true) -> "image/png"
        name.endsWith(".gif", true) -> "image/gif"
        name.endsWith(".webp", true) -> "image/webp"
        else -> null
    }

    private fun compress(raw: ByteArray): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return raw

        var sample = 1
        while (bounds.outWidth / sample > MAX_IMAGE_EDGE || bounds.outHeight / sample > MAX_IMAGE_EDGE) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, opts) ?: return raw
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bmp.recycle()
        return out.toByteArray()
    }

    // ==================== 音频 ====================

    private fun prepareAudio(ctx: Context, pending: Pending): Prepared.Audio {
        val bytes = ctx.contentResolver.openInputStream(pending.uri)?.use { it.readBytes() }
            ?: throw UnsupportedException("无法读取文件：${pending.name}")
        if (bytes.isEmpty()) throw UnsupportedException("音频内容为空：${pending.name}")
        if (bytes.size > MAX_AUDIO_BYTES) {
            throw UnsupportedException("音频过大（>12MB），请压缩后重试：${pending.name}")
        }
        val mime = pending.mime.ifBlank { audioMimeOf(pending.name) }
        return Prepared.Audio(
            name = pending.name,
            mime = mime,
            format = audioFormatOf(pending.name, mime),
            base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
        )
    }

    /** OpenAI `input_audio.format` 仅接受 mp3 / wav，这里优先映射到这两个值。 */
    private fun audioFormatOf(name: String, mime: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            mime.contains("mpeg") || ext == "mp3" -> "mp3"
            mime.contains("wav") || ext == "wav" -> "wav"
            mime.contains("mp4") || mime.contains("m4a") || ext == "m4a" -> "mp4"
            mime.contains("aac") || ext == "aac" -> "aac"
            mime.contains("ogg") || ext == "ogg" || ext == "oga" -> "ogg"
            mime.contains("flac") || ext == "flac" -> "flac"
            mime.contains("amr") || ext == "amr" -> "amr"
            mime.contains("opus") || ext == "opus" -> "opus"
            ext.isNotBlank() -> ext
            else -> "mp3"
        }
    }

    private fun audioMimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "ogg", "oga" -> "audio/ogg"
        "flac" -> "audio/flac"
        "amr" -> "audio/amr"
        "opus" -> "audio/opus"
        "3gp", "3gpp" -> "audio/3gpp"
        else -> "audio/*"
    }

    // ==================== PDF ====================

    private fun renderPdf(ctx: Context, uri: Uri): List<String> {
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r")
            ?: throw UnsupportedException("无法打开 PDF")
        pfd.use { fd ->
            PdfRenderer(fd).use { renderer ->
                val count = minOf(renderer.pageCount, MAX_PDF_PAGES)
                val result = ArrayList<String>(count)
                for (i in 0 until count) {
                    renderer.openPage(i).use { page ->
                        val scale = IMAGE_TARGET_PX.toFloat() / maxOf(page.width, 1)
                        val w = (page.width * scale).toInt().coerceAtLeast(1)
                        val h = (page.height * scale).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val out = ByteArrayOutputStream()
                        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
                        bmp.recycle()
                        result.add(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
                    }
                }
                return result
            }
        }
    }

    // ==================== Office (OOXML) ====================

    private fun readZipText(ctx: Context, uri: Uri, accept: (String) -> Boolean): String {
        val sb = StringBuilder()
        ctx.contentResolver.openInputStream(uri)?.use { stream ->
            ZipInputStream(stream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && accept(entry.name)) {
                        val xml = zip.readBytes().toString(Charsets.UTF_8)
                        val text = xmlToText(xml)
                        if (text.isNotBlank()) {
                            if (sb.isNotEmpty()) sb.append("\n")
                            sb.append(text.trim())
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } ?: throw UnsupportedException("无法读取文件")

        if (sb.isBlank()) throw UnsupportedException("未能从文档中提取到文本")
        return sb.toString().take(MAX_TEXT_CHARS)
    }

    private fun xmlToText(xml: String): String {
        val withBreaks = xml
            .replace(Regex("</w:p>|</a:p>|<w:br\\s*/>|<a:br\\s*/>|</w:tr>"), "\n")
            .replace(Regex("</w:tc>|</a:tc>"), "\t")
        val noTags = withBreaks.replace(Regex("<[^>]+>"), "")
        return decodeEntities(noTags)
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    private fun decodeEntities(s: String): String {
        var out = s
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'")
            .replace("&amp;", "&")
        out = Regex("&#x([0-9a-fA-F]+);").replace(out) {
            runCatching { String(Character.toChars(it.groupValues[1].toInt(16))) }.getOrDefault("")
        }
        out = Regex("&#(\\d+);").replace(out) {
            runCatching { String(Character.toChars(it.groupValues[1].toInt())) }.getOrDefault("")
        }
        return out
    }

    // ==================== 文本 ====================

    private fun readText(ctx: Context, uri: Uri): String {
        val text = ctx.contentResolver.openInputStream(uri)?.use { stream ->
            stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } ?: throw UnsupportedException("无法读取文件")
        if (text.isBlank()) throw UnsupportedException("文件内容为空")
        return text.take(MAX_TEXT_CHARS)
    }
}

package app.azcode.bridge

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.text.method.LinkMovementMethod
import android.widget.TextView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.latex.JLatexMathTheme
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin

/**
 * 基于 Markwon 的 markdown 渲染辅助。所有助手文本统一走此渲染，
 * 支持标题、加粗/斜体、代码块、列表、引用、链接、表格、删除线、任务列表，
 * 以及 `$...$`（行内）与 `$$...$$`（块级）LaTeX 数学公式。
 * 公式由 jlatexmath-android 在本地排版，不依赖网络。
 *
 * 注意：Markwon 的 ext-latex 只识别 `$$...$$`，因此渲染前会把 `$...$`、
 * `\(...\)`、`\[...\]` 等分隔符统一规范化为 `$$...$$`，并修正 jlatexmath
 * 不支持的写法（见 [normalizeLatex] / [sanitizeMath]）。
 * 图片链接由 [extractImages] 分离后另行下载展示。
 */
object Markdown {

    @Volatile
    private var instance: Markwon? = null

    fun render(tv: TextView, md: String) {
        tv.movementMethod = LinkMovementMethod.getInstance()
        val normalized = normalizeLatex(md)
        runCatching { markwon(tv.context).setMarkdown(tv, normalized) }
            .onFailure { tv.text = md }
    }

    /**
     * 把文本渲染成带公式的 [CharSequence]，用于 [TextView] 之外无法直接走
     * [Markwon.setMarkdown] 的控件（如 RadioButton / CheckBox 的选项文案）。
     * 渲染失败时原样返回，保证文案不丢失。
     */
    fun toSpanned(context: Context, md: String): CharSequence =
        runCatching { markwon(context).toMarkdown(normalizeLatex(md)) }.getOrDefault(md)

    /**
     * 单行、无块级元素的文本（会话标题、模型名、按钮文案等）走轻量渲染：
     * 只做行内公式替换，避免 Markwon 把标题里的 `#`、`-` 等当成结构语法。
     */
    fun renderInline(tv: TextView, text: String) {
        val normalized = normalizeLatex(text)
        if (normalized == text) {
            tv.text = text
            return
        }
        runCatching { markwon(tv.context).setMarkdown(tv, normalized) }
            .onFailure { tv.text = text }
    }

    /**
     * 把所有数学分隔符统一改写成 ext-latex 能识别的 `$$...$$`：
     * - `\(...\)`（LaTeX 行内）、`\[...\]`（LaTeX 块级）→ `$$...$$`
     * - 单 `$...$` 行内 → `$$...$$`（仅当内容像公式时）
     * - 已是 `$$...$$` 的保持原样
     *
     * 同时修正公式体里常见的写法差异（见 [sanitizeMath]），例如模型会把
     * `array` 的换行 `\\[2ex]` 误写成 `\[2ex]`，jlatexmath 会把 `\[` 当成
     * 未闭合的块级公式而报错。会跳过代码块与行内代码，避免误伤代码里的 `$`。
     */
    fun normalizeLatex(md: String): String {
        if (!md.contains('$') && !md.contains("\\(") && !md.contains("\\[")) return md
        val out = StringBuilder(md.length + 64)
        val n = md.length
        var i = 0
        var inFence = false
        while (i < n) {
            // 行首判断代码围栏，围栏内不做任何公式改写。
            if (i == 0 || md[i - 1] == '\n') {
                val lineEnd = md.indexOf('\n', i).let { if (it < 0) n else it }
                val trimmed = md.substring(i, lineEnd).trimStart()
                if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                    inFence = !inFence
                    out.append(md, i, lineEnd)
                    i = lineEnd
                    continue
                }
            }
            if (inFence) {
                out.append(md[i])
                i++
                continue
            }
            val c = md[i]
            if (c == '`') {
                // 行内代码整体跳过。
                val end = md.indexOf('`', i + 1)
                if (end < 0) {
                    out.append(md, i, n)
                    break
                }
                out.append(md, i, end + 1)
                i = end + 1
                continue
            }
            if (c == '\\' && i + 1 < n) {
                // array 换行 `\\[2ex]` 及其漏写反斜杠的 `\[2ex]`，统一为 `\\ `。
                val rowBreak = matchRowBreakAt(md, i)
                if (rowBreak != null) {
                    out.append("\\\\ ")
                    i = rowBreak.last + 1
                    continue
                }
                val d = md[i + 1]
                if (d == '$') {
                    // 转义的 \$ 原样保留。
                    out.append("\\$")
                    i += 2
                    continue
                }
                if (d == '(') {
                    val close = md.indexOf("\\)", i + 2)
                    if (close >= 0) {
                        appendMath(out, md.substring(i + 2, close))
                        i = close + 2
                        continue
                    }
                }
                if (d == '[') {
                    val close = md.indexOf("\\]", i + 2)
                    if (close >= 0) {
                        appendMath(out, md.substring(i + 2, close))
                        i = close + 2
                        continue
                    }
                }
                out.append(c)
                i++
                continue
            }
            if (c == '$') {
                if (i + 1 < n && md[i + 1] == '$') {
                    val close = md.indexOf("$$", i + 2)
                    if (close >= 0) {
                        appendMath(out, md.substring(i + 2, close))
                        i = close + 2
                        continue
                    }
                    out.append("$$")
                    i += 2
                    continue
                }
                val close = md.indexOf('$', i + 1)
                if (close > i + 1) {
                    val body = md.substring(i + 1, close)
                    if (looksLikeMath(body)) {
                        appendMath(out, body)
                        i = close + 1
                        continue
                    }
                }
                // 未闭合或不像公式的单个 $，按普通字符输出。
                out.append(c)
                i++
                continue
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    /** 把一段公式体包成 `$$...$$`，并做兼容性修正。 */
    private fun appendMath(out: StringBuilder, body: String) {
        out.append("$$").append(sanitizeMath(body)).append("$$")
    }

    /**
     * 修正公式体里 jlatexmath 无法直接处理、但从模型输出中很常见的写法：
     * - `\[2ex]` / `\[1.5ex]`（漏写一个反斜杠的换行间距）→ `\\ `
     * - 不支持的字号开关（`\normalsize` 等）直接去掉，避免整段公式解析失败
     */
    private fun sanitizeMath(body: String): String {
        var s = body
        if (s.contains("\\[")) {
            s = ROW_BREAK_RE.replace(s) { "\\\\ " }
        }
        if (s.contains("\\normalsize") || s.contains("\\tiny") || s.contains("\\scriptsize") ||
            s.contains("\\footnotesize") || s.contains("\\huge") || s.contains("\\Huge") ||
            s.contains("\\normalfont")
        ) {
            UNSUPPORTED_SIZE_RE.replace(s, "").let { s = it }
        }
        return s
    }

    /**
     * 匹配 `array` 换行间距：允许一到两个反斜杠前缀，间距写法如 `2ex`、`1.5em`。
     * 起始位置必须正好是 [index]，避免误伤后面的其它公式。
     */
    private fun matchRowBreakAt(md: String, index: Int): IntRange? {
        val m = ROW_BREAK_RE.find(md, index) ?: return null
        return if (m.range.first == index) m.range else null
    }

    private val ROW_BREAK_RE =
        Regex("""\\{1,2}\[\s*[\d.]+\s*(?:ex|em|pt|pc|cm|mm|in|bp|dd|cc|sp|mu)\s*]""")

    private val UNSUPPORTED_SIZE_RE =
        Regex("""\\(?:normalsize|tiny|scriptsize|footnotesize|huge|Huge|normalfont)\b""")

    /**
     * 判断 `$...$` 中间的内容是否像数学公式，避免把「价格 5$ 和 10$」这类货币文本误转。
     * 含数学符号或字母即视为公式；纯数字（如 `$100$`）不算。
     */
    private fun looksLikeMath(body: String): Boolean {
        if (body.isEmpty() || body.length > 400) return false
        if (body.first() == ' ' || body.last() == ' ') return false
        if (body.contains('\n')) return false
        var hasSymbol = false
        var hasLetter = false
        body.forEach { ch ->
            if (MATH_SYMBOLS.indexOf(ch) >= 0) hasSymbol = true
            if (ch.isLetter()) hasLetter = true
        }
        return hasSymbol || hasLetter
    }

    private const val MATH_SYMBOLS = "\\^_{}=+-*/<>()[]|&%"

    /** 提取 markdown 图片语法 `![alt](url)` 中的 URL，返回 (替换掉图片语法的文本, 图片 URL 列表)。 */
    fun extractImages(md: String): Pair<String, List<String>> {
        val urls = mutableListOf<String>()
        val cleaned = IMAGE_RE.replace(md) { m ->
            val url = m.groupValues[1]
            if (url.isNotBlank()) urls.add(url)
            ""
        }
        return cleaned to urls
    }

    private val IMAGE_RE = Regex("!\\[[^\\]]*]\\(([^)\\s]+)\\)")

    /** 公式不做背景色，与消息气泡融为一体。 */
    private val TRANSPARENT_BACKGROUND = object : JLatexMathTheme.BackgroundProvider {
        override fun provide(): Drawable = ColorDrawable(Color.TRANSPARENT)
    }

    private fun markwon(context: Context): Markwon {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            val app = context.applicationContext
            val textColor = runCatching { app.getColor(R.color.text_primary) }.getOrDefault(Color.BLACK)
            // create(inlineTextSize, blockTextSize, configure)：两个字号单位是 px。
            // 用单参重载会把块级字号留成 0，公式会被画成一个点，因此显式传两个字号。
            val metrics = app.resources.displayMetrics
            @Suppress("DEPRECATION")
            val fontScale = app.resources.configuration.fontScale
            val inlineSizePx = 16f * metrics.density * fontScale
            val blockSizePx = 17f * metrics.density * fontScale
            val m = runCatching {
                Markwon.builder(app)
                    .usePlugin(MarkwonInlineParserPlugin.create())
                    .usePlugin(TablePlugin.create(app))
                    .usePlugin(StrikethroughPlugin.create())
                    .usePlugin(TaskListPlugin.create(app))
                    .usePlugin(
                        JLatexMathPlugin.create(inlineSizePx, blockSizePx) { builder ->
                            builder.inlinesEnabled(true)
                            builder.theme().textColor(textColor)
                            builder.theme().backgroundProvider(TRANSPARENT_BACKGROUND)
                        },
                    )
                    .build()
            }.getOrElse {
                // 公式插件初始化失败时退回基础 markdown，保证消息仍可阅读。
                Markwon.builder(app)
                    .usePlugin(MarkwonInlineParserPlugin.create())
                    .usePlugin(TablePlugin.create(app))
                    .usePlugin(StrikethroughPlugin.create())
                    .usePlugin(TaskListPlugin.create(app))
                    .build()
            }
            instance = m
            return m
        }
    }
}

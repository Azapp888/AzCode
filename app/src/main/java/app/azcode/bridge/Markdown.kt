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
 * 图片链接由 [extractImages] 分离后另行下载展示。
 */
object Markdown {

    @Volatile
    private var instance: Markwon? = null

    fun render(tv: TextView, md: String) {
        tv.movementMethod = LinkMovementMethod.getInstance()
        runCatching { markwon(tv.context).setMarkdown(tv, md) }
            .onFailure { tv.text = md }
    }

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
            @Suppress("DEPRECATION")
            val density = app.resources.displayMetrics.scaledDensity
            val m = runCatching {
                Markwon.builder(app)
                    .usePlugin(MarkwonInlineParserPlugin.create())
                    .usePlugin(TablePlugin.create(app))
                    .usePlugin(StrikethroughPlugin.create())
                    .usePlugin(TaskListPlugin.create(app))
                    .usePlugin(
                        JLatexMathPlugin.create(density) { builder ->
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

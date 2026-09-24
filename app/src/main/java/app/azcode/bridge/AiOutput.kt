package app.azcode.bridge

/**
 * 从 AI 回复中提取适合在悬浮气泡展示的纯文本：剔除代码行。
 *
 * 悬浮气泡用于在 AI 操作手机的过程中提示用户，代码行本身会留在聊天区，
 * 因而这里把围栏代码块、缩进代码行过滤掉，只保留自然语言说明。
 */
object AiOutput {

    fun textOnly(raw: String?): String? {
        val src = raw?.trim().orEmpty()
        if (src.isEmpty()) return null

        val sb = StringBuilder()
        var inFence = false
        src.lineSequence().forEach { line ->
            val trimmedStart = line.trimStart()
            if (trimmedStart.startsWith("```")) {
                inFence = !inFence
                return@forEach
            }
            if (inFence) return@forEach
            // 四个空格或 Tab 缩进的整行按代码处理。
            if (line.startsWith("    ") || line.startsWith("\t")) return@forEach
            sb.append(line).append('\n')
        }
        return sb.toString().trim().ifBlank { null }
    }
}

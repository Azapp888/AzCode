package app.azcode.bridge

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃与应用日志落盘。
 *
 * 背景：此前应用没有任何全局异常捕获，闪退后既不写 Logcat（release 下常被过滤）也不留文件，
 * 导致「运行几下就停止运行」无法定位。这里安装 [Thread.setDefaultUncaughtExceptionHandler]，
 * 把未捕获异常连同线程、版本、时间写入 files/logs/，同时保留系统默认行为（继续崩溃）。
 *
 * 日志按天轮转，最多保留 [MAX_FILES] 个文件，单文件超过 [MAX_BYTES] 截断，避免无限增长。
 */
object CrashLog {

    private const val TAG = "AzCode"
    private const val DIR = "logs"
    private const val MAX_FILES = 10
    private const val MAX_BYTES = 512 * 1024

    @Volatile
    private var installed = false

    @Volatile
    private var appContext: Context? = null

    /** 记录普通事件，便于回溯崩溃前的执行轨迹。 */
    fun i(tag: String, message: String) = write("I", tag, message, null)

    fun w(tag: String, message: String, t: Throwable? = null) = write("W", tag, message, t)

    fun e(tag: String, message: String, t: Throwable? = null) = write("E", tag, message, t)

    /** 在 Application.onCreate 中调用，幂等。 */
    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            appContext = context.applicationContext
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                runCatching { write("F", "crash", "线程 ${thread.name} 未捕获异常", throwable) }
                // 交还系统默认处理，保持系统行为一致（闪退对话框、重启等）。
                previous?.uncaughtException(thread, throwable)
            }
            installed = true
            i(TAG, "CrashLog installed")
        }
    }

    /** 所有日志文件的绝对路径，按时间倒序。 */
    fun files(context: Context): List<File> =
        dir(context).listFiles { f -> f.isFile && f.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    fun readAll(context: Context, limit: Int = 120_000): String {
        val all = files(context)
        if (all.isEmpty()) return ""
        val sb = StringBuilder()
        for (f in all) {
            sb.append("===== ").append(f.name).append(" =====\n")
            sb.append(runCatching { f.readText() }.getOrDefault(""))
            sb.append('\n')
            if (sb.length > limit) break
        }
        return sb.toString().take(limit)
    }

    fun clear(context: Context) {
        files(context).forEach { runCatching { it.delete() } }
    }

    private fun dir(context: Context): File =
        File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }

    private fun write(level: String, tag: String, message: String, t: Throwable?) {
        // 同时写 Logcat，便于 adb 调试。
        when (level) {
            "F" -> Log.e(tag, message, t)
            "E" -> Log.e(tag, message, t)
            "W" -> Log.w(tag, message, t)
            else -> Log.i(tag, message)
        }
        val ctx = appContext ?: return
        runCatching {
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val file = File(dir(ctx), dayStamp() + ".log")
            if (file.exists() && file.length() > MAX_BYTES) return
            val line = StringBuilder()
                .append(stamp).append(' ')
                .append(level).append('/').append(tag).append(": ")
                .append(message)
            if (t != null) {
                val sw = StringWriter()
                t.printStackTrace(PrintWriter(sw))
                line.append('\n').append(sw.toString().trimEnd())
            }
            line.append('\n')
            file.appendText(line.toString())
            prune(ctx)
        }
    }

    private fun dayStamp(): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    private fun prune(context: Context) {
        val all = files(context)
        if (all.size <= MAX_FILES) return
        all.drop(MAX_FILES).forEach { runCatching { it.delete() } }
    }
}

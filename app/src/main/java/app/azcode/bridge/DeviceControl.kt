package app.azcode.bridge

import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 设备控制能力：权限模式 + 命令执行通道。
 *
 *  - NORMAL   : 以内置 shell（/system/bin/sh）以应用自身 uid 执行，无需 Root/Shizuku
 *  - SHIZUKU  : 经 Shizuku binder 以 adb(uid 2000) 身份执行 shell
 *  - ROOT     : 经 su 以 uid 0 执行 shell
 *
 * NORMAL 即「内置命令行」：可直接跑 sh、ls、cat、getprop、pm、am 等本机命令，
 * 但受应用沙箱限制（访问其他应用私有目录、系统设置等仍需 SHIZUKU/ROOT）。
 */
enum class PrivMode(val label: String) {
    NORMAL("本机（应用权限）"),
    SHIZUKU("Shizuku"),
    ROOT("Root");

    companion object {
        fun from(key: String?): PrivMode =
            entries.firstOrNull { it.name == key } ?: NORMAL
    }
}

object DeviceControl {

    private const val TAG = "DeviceControl"
    private const val PREFS = "azcode_priv"
    private const val KEY_MODE = "priv_mode"

    /** 命令执行超时，避免交互式命令挂死 Agent。 */
    private const val EXEC_TIMEOUT_SECONDS = 60L

    /** Termux 命令更重（可跑 pip/npm/git），给更长的超时。 */
    private const val TERMUX_TIMEOUT_MS = 90_000L

    private val SU_PATHS = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su",
        "/vendor/bin/su", "/data/adb/magisk/su", "/data/adb/ksu/bin/su",
    )

    private val SHELL_PATHS = listOf(
        "/system/bin/sh", "/system/xbin/sh", "/vendor/bin/sh",
    )

    /** 可用的内置 shell；找不到时退回 PATH 中的 sh。 */
    private fun shellPath(): String =
        SHELL_PATHS.firstOrNull { File(it).exists() } ?: "sh"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getMode(ctx: Context): PrivMode =
        PrivMode.from(prefs(ctx).getString(KEY_MODE, PrivMode.NORMAL.name))

    fun setMode(ctx: Context, mode: PrivMode) {
        prefs(ctx).edit().putString(KEY_MODE, mode.name).apply()
    }

    /** 循环 NORMAL → SHIZUKU → ROOT → NORMAL，返回新值 */
    fun cycleMode(ctx: Context): PrivMode {
        val next = when (getMode(ctx)) {
            PrivMode.NORMAL -> PrivMode.SHIZUKU
            PrivMode.SHIZUKU -> PrivMode.ROOT
            PrivMode.ROOT -> PrivMode.NORMAL
        }
        setMode(ctx, next)
        return next
    }

    fun rootAvailable(): Boolean = SU_PATHS.any { java.io.File(it).exists() }

    fun shizukuServerRunning(): Boolean = runCatching { rikka.shizuku.Shizuku.pingBinder() }.getOrDefault(false)

    fun shizukuGranted(): Boolean = runCatching {
        rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun shizukuUsable(): Boolean = shizukuServerRunning() && shizukuGranted()

    fun requestShizukuPermission(requestCode: Int) {
        runCatching { rikka.shizuku.Shizuku.requestPermission(requestCode) }
            .onFailure { Log.w(TAG, "requestShizukuPermission: ${it.message}") }
    }

    /**
     * 按当前模式执行 shell 命令。
     * NORMAL 模式优先走 Termux（若已安装并授权），否则用内置 shell 以应用自身权限执行；
     * 命令始终可用，不再直接失败。
     */
    fun exec(ctx: Context, cmd: String): String {
        if (cmd.isBlank()) return "azcode: empty command\n"
        return when (getMode(ctx)) {
            PrivMode.SHIZUKU -> if (shizukuUsable()) {
                runCatching { shizukuExec(cmd) }
                    .getOrElse { runCatching { preferredExec(ctx, cmd) }.getOrElse { localExec(ctx, cmd) } }
            } else {
                runCatching { preferredExec(ctx, cmd) }
                    .getOrElse { "shizuku not usable (server running=${shizukuServerRunning()}, granted=${shizukuGranted()}); fallback error: ${it.message}\n" }
            }
            PrivMode.ROOT -> if (rootAvailable()) {
                runCatching { rootExec(cmd) }
                    .getOrElse { runCatching { preferredExec(ctx, cmd) }.getOrElse { localExec(ctx, cmd) } }
            } else {
                runCatching { preferredExec(ctx, cmd) }
                    .getOrElse { "root not available; fallback error: ${it.message}\n" }
            }
            PrivMode.NORMAL -> runCatching { preferredExec(ctx, cmd) }
                .getOrElse { "shell error: ${it.message}\n" }
        }
    }

    /**
     * 优先 Termux（完整 Linux：python/node/git/pip 等），不可用或失败时退回内置 shell。
     */
    private fun preferredExec(ctx: Context, cmd: String): String {
        if (TermuxControl.isReady(ctx)) {
            val r = runCatching { TermuxControl.exec(ctx, cmd, TERMUX_TIMEOUT_MS) }
            r.getOrNull()?.let { return it }
            Log.w(TAG, "termux exec failed, fallback to builtin shell: ${r.exceptionOrNull()?.message}")
        }
        return localExec(ctx, cmd)
    }

    /**
     * 内置 shell：以应用自身 uid 执行命令，无需 Root/Shizuku。
     * 工作目录设为应用私有目录，PATH 覆盖系统常见 bin 目录。
     */
    private fun localExec(ctx: Context, cmd: String): String {
        val pb = ProcessBuilder(shellPath(), "-c", cmd)
        pb.redirectErrorStream(true)
        pb.directory(ctx.filesDir)
        val env = pb.environment()
        env["PATH"] = "/product/bin:/apex/com.android.runtime/bin:/system/bin:/system/xbin:/vendor/bin"
        env["HOME"] = ctx.filesDir.absolutePath
        env["TMPDIR"] = ctx.cacheDir.absolutePath
        env["PWD"] = ctx.filesDir.absolutePath

        val process = pb.start()
        process.outputStream.close()

        val sb = StringBuilder()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().use { r ->
                    val buf = CharArray(4096)
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        sb.append(buf, 0, n)
                    }
                }
            }
        }
        reader.isDaemon = true
        reader.start()

        val finished = process.waitFor(EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            reader.join(1000)
            return (sb.toString() + "\n[命令超时（>${EXEC_TIMEOUT_SECONDS}s），已终止]").trim() + "\n"
        }
        reader.join(2000)
        return sb.toString()
    }

    /** 以 Shizuku(adb uid 2000) 身份执行，返回 stdout+stderr 合并 */
    private fun shizukuExec(cmd: String): String {
        val svc = moe.shizuku.server.IShizukuService.Stub.asInterface(rikka.shizuku.Shizuku.getBinder())
            ?: throw IllegalStateException("Shizuku binder unavailable")
        val rp = svc.newProcess(
            arrayOf("sh", "-c", cmd),
            arrayOf("PATH=/system/bin:/system/xbin"),
            null,
        )
        val out = ParcelFileDescriptor.AutoCloseInputStream(rp.inputStream).bufferedReader().readText()
        val err = ParcelFileDescriptor.AutoCloseInputStream(rp.errorStream).bufferedReader().readText()
        rp.waitFor()
        return out + err
    }

    /** 以 su(uid 0) 身份执行 */
    private fun rootExec(cmd: String): String {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return out
    }
}

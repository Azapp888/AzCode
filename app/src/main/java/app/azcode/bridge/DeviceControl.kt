package app.azcode.bridge

import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.util.Log

/**
 * 设备控制能力：权限模式 + 命令执行通道。
 *
 * 复用于 DSH Mobile (MIT) 的 Shizuku 集成思路：
 *  - NORMAL   : 仅应用沙箱，/shell 不可用
 *  - SHIZUKU  : 经 Shizuku binder 以 adb(uid 2000) 身份执行 shell
 *  - ROOT     : 经 su 以 uid 0 执行 shell
 *
 * 注意：Shizuku 无法改变子进程 uid，因此这里只把「执行通道」提权，应用本体仍为沙箱。
 */
enum class PrivMode { NORMAL, SHIZUKU, ROOT }

object DeviceControl {

    private const val TAG = "DeviceControl"
    private const val PREFS = "azcode_priv"
    private const val KEY_MODE = "priv_mode"

    private val SU_PATHS = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su",
        "/vendor/bin/su", "/data/adb/magisk/su", "/data/adb/ksu/bin/su",
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getMode(ctx: Context): PrivMode = runCatching {
        PrivMode.valueOf(prefs(ctx).getString(KEY_MODE, PrivMode.NORMAL.name)!!)
    }.getOrDefault(PrivMode.NORMAL)

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
     * NORMAL 模式返回错误说明，不静默失败。
     */
    fun exec(ctx: Context, cmd: String): String {
        if (cmd.isBlank()) return "azcode: empty command\n"
        return when (getMode(ctx)) {
            PrivMode.SHIZUKU -> if (shizukuUsable()) {
                runCatching { shizukuExec(cmd) }.getOrElse { "shizuku error: ${it.message}\n" }
            } else {
                "shizuku not usable (server running=${shizukuServerRunning()}, granted=${shizukuGranted()})\n"
            }
            PrivMode.ROOT -> runCatching { rootExec(cmd) }.getOrElse { "su error: ${it.message}\n" }
            PrivMode.NORMAL -> "shell unavailable in NORMAL mode; switch to SHIZUKU or ROOT\n"
        }
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

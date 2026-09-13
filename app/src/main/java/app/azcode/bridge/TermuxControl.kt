package app.azcode.bridge

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 通过 Termux 的 RUN_COMMAND 接口调用完整 Linux 环境。
 *
 * Termux 是独立 App，前缀（$PREFIX）数百 MB，无法塞进本 APK。若用户已安装 Termux，
 * 本 App 可以借助其 RUN_COMMAND 服务，在 Termux 的 uid 下运行 bash 与已安装的软件包
 * （python、node、git、ffmpeg 等），拿到 stdout/stderr/exit code。
 *
 * 使用前置条件（缺一不可）：
 *  1. 设备已安装 Termux（com.termux）；
 *  2. 本 App 已获得 com.termux.permission.RUN_COMMAND 运行时权限；
 *  3. Termux 的 ~/.termux/termux.properties 中设置了 allow-external-apps=true。
 *
 * 任一条不满足时不抛异常/自动回退，由调用方决定是否退回内置 shell。
 */
object TermuxControl {

    private const val TAG = "TermuxControl"

    const val PACKAGE = "com.termux"
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"

    private const val SERVICE = "com.termux.app.RunCommandService"
    private const val ACTION = "com.termux.RUN_COMMAND"

    private const val EXTRA_PATH = "com.termux.RUN_COMMAND_PATH"
    private const val EXTRA_ARGS = "com.termux.RUN_COMMAND_ARGUMENTS"
    private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    private const val EXTRA_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
    private const val EXTRA_DESCRIPTION = "com.termux.RUN_COMMAND_COMMAND_DESCRIPTION"
    private const val EXTRA_PENDING = "com.termux.RUN_COMMAND_PENDING_INTENT"

    private const val RESULT_BUNDLE = "result"
    private const val RESULT_STDOUT = "stdout"
    private const val RESULT_STDERR = "stderr"
    private const val RESULT_EXIT = "exitCode"
    private const val RESULT_ERRMSG = "errmsg"

    private const val BASH = "/data/data/com.termux/files/usr/bin/bash"
    private const val HOME = "/data/data/com.termux/files/home"

    private val seq = AtomicInteger(0)

    fun isInstalled(ctx: Context): Boolean = runCatching {
        ctx.packageManager.getPackageInfo(PACKAGE, 0)
        true
    }.getOrDefault(false)

    fun isPermissionGranted(ctx: Context): Boolean = runCatching {
        ctx.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** 已安装且已授权，可尝试调用。 */
    fun isReady(ctx: Context): Boolean = isInstalled(ctx) && isPermissionGranted(ctx)

    /**
     * 在 Termux 中执行命令并等待结果，返回 stdout+stderr 合并文本。
     * 调用方应保证运行在后台线程；超时返回提示而非无限阻塞。
     */
    fun exec(ctx: Context, cmd: String, timeoutMs: Long): String {
        val requestCode = seq.incrementAndGet()
        val action = "${ctx.packageName}.TERMUX_RESULT.$requestCode"

        val latch = CountDownLatch(1)
        var output: String? = null

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                output = parseResult(intent)
                latch.countDown()
            }
        }

        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            ctx.registerReceiver(receiver, filter)
        }

        try {
            val resultIntent = Intent(action).setPackage(ctx.packageName)
            val pending = PendingIntent.getBroadcast(
                ctx,
                requestCode,
                resultIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )

            val svc = Intent().apply {
                setClassName(PACKAGE, SERVICE)
                this.action = ACTION
                putExtra(EXTRA_PATH, BASH)
                putExtra(EXTRA_ARGS, arrayOf("-c", cmd))
                putExtra(EXTRA_WORKDIR, HOME)
                putExtra(EXTRA_BACKGROUND, true)
                putExtra(EXTRA_LABEL, "AzCode")
                putExtra(EXTRA_DESCRIPTION, cmd.take(200))
                putExtra(EXTRA_PENDING, pending)
            }
            ctx.startService(svc)

            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                return "[Termux 执行超时（>${timeoutMs / 1000}s）]"
            }
            return output ?: ""
        } finally {
            runCatching { ctx.unregisterReceiver(receiver) }
                .onFailure { Log.w(TAG, "unregisterReceiver: ${it.message}") }
        }
    }

    /** Termux 通过 PendingIntent 回传的 result Bundle：stdout/stderr/exitCode/err/errmsg。 */
    private fun parseResult(intent: Intent): String {
        val b: Bundle? = intent.getBundleExtra(RESULT_BUNDLE)
        if (b == null) {
            return intent.getStringExtra(RESULT_STDOUT).orEmpty() +
                intent.getStringExtra(RESULT_STDERR).orEmpty()
        }
        val stdout = b.getString(RESULT_STDOUT).orEmpty()
        val stderr = b.getString(RESULT_STDERR).orEmpty()
        val errmsg = b.getString(RESULT_ERRMSG)
        val exit = b.getInt(RESULT_EXIT)

        val sb = StringBuilder()
        sb.append(stdout)
        if (stderr.isNotEmpty()) {
            if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append('\n')
            sb.append(stderr)
        }
        if (!errmsg.isNullOrEmpty()) {
            if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append('\n')
            sb.append(errmsg)
        }
        if (exit != 0) {
            if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append('\n')
            sb.append("[exit ").append(exit).append(']')
        }
        return sb.toString()
    }

    /** 供设置页展示的简短状态。 */
    fun statusText(ctx: Context): String = when {
        !isInstalled(ctx) -> "Termux 未安装"
        !isPermissionGranted(ctx) -> "Termux 已安装，未授权"
        else -> "Termux 可用"
    }
}

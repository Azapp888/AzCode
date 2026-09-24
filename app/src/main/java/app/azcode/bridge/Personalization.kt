package app.azcode.bridge

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * 本机个性化数据与授权说明。
 *
 * AzCode 需要读取本机已安装应用的包名与各应用声明的 Android 权限，才能理解「用户装了哪些应用、
 * 它们大致能做什么」，从而把任务落到正确的 App 上。这些数据：
 *  - 仅在设备本地生成并保存在应用私有目录；
 *  - 不注入与云端大模型的对话上下文，因此不会上传到任何服务器；
 *  - 用户可随时在「设置 → 个性化与隐私」中查看、重新生成或清除。
 *
 * 本对象负责授权状态的持久化与本地快照的生成。首启且用户已开启无障碍服务后，会展示一次授权说明。
 */
object Personalization {

    private const val PREFS = "azcode_personalization"
    private const val KEY_CONSENTED = "consented"
    private const val KEY_CONSENTED_AT = "consented_at"
    private const val KEY_SNAPSHOT_AT = "snapshot_at"
    private const val KEY_PROMPT_SHOWN = "prompt_shown"

    private const val MAX_APPS = 400

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 首启授权说明是否已展示过（无论用户当时是否同意，都只弹一次）。 */
    fun promptShown(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_PROMPT_SHOWN, false)

    fun markPromptShown(ctx: Context) {
        prefs(ctx).edit().putBoolean(KEY_PROMPT_SHOWN, true).apply()
    }

    /** 用户是否已阅读并同意个性化数据说明。 */
    fun hasConsented(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_CONSENTED, false)

    fun consentedAt(ctx: Context): Long = prefs(ctx).getLong(KEY_CONSENTED_AT, 0L)

    fun snapshotAt(ctx: Context): Long = prefs(ctx).getLong(KEY_SNAPSHOT_AT, 0L)

    fun setConsented(ctx: Context) {
        prefs(ctx).edit()
            .putBoolean(KEY_CONSENTED, true)
            .putLong(KEY_CONSENTED_AT, System.currentTimeMillis())
            .apply()
    }

    /** 撤销同意并清除本地个性化数据。 */
    fun revoke(ctx: Context) {
        prefs(ctx).edit()
            .putBoolean(KEY_CONSENTED, false)
            .putLong(KEY_CONSENTED_AT, 0L)
            .putLong(KEY_SNAPSHOT_AT, 0L)
            .apply()
        MemoryStore.clearLocal(ctx)
    }

    /** 已安装应用条数（用于界面上显示概览）。 */
    fun installedAppCount(ctx: Context): Int = runCatching {
        val pm = ctx.packageManager
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(0)
        }.size
    }.getOrDefault(0)

    /**
     * 采集本机已安装应用与权限，写入本地记忆（LOCAL 分类）。耗时，需在工作线程调用。
     * 返回生成的条目条数。
     */
    fun refreshSnapshot(ctx: Context): Int {
        val text = buildSnapshot(ctx)
        MemoryStore.upsertLocal(ctx, ctx.getString(R.string.personalization_memory_title), text)
        prefs(ctx).edit().putLong(KEY_SNAPSHOT_AT, System.currentTimeMillis()).apply()
        return text.lines().count { it.startsWith("- ") }
    }

    /** 生成可读的本地快照文本。 */
    fun buildSnapshot(ctx: Context): String {
        val pm = ctx.packageManager
        val packages = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(0)
            }
        }.getOrDefault(emptyList())

        val sb = StringBuilder()
        sb.append("（本机数据，仅保存在本地，不参与云端对话）\n")
        sb.append("设备：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append("，Android ").append(Build.VERSION.RELEASE).append('\n')
        sb.append("已安装应用共 ").append(packages.size).append(" 个，以下为可供选择的常用应用：\n")

        packages.asSequence()
            .filter { it.packageName != ctx.packageName }
            .sortedBy { it.packageName }
            .take(MAX_APPS)
            .forEach { pkg ->
                val label = runCatching { pm.getApplicationLabel(pkg.applicationInfo!!).toString() }
                    .getOrDefault(pkg.packageName)
                val perms = pkg.requestedPermissions
                    ?.filter { it.startsWith("android.permission.") }
                    ?.map { it.removePrefix("android.permission.") }
                    .orEmpty()
                sb.append("- ").append(label).append("（").append(pkg.packageName).append("）")
                if (perms.isNotEmpty()) sb.append("；权限：").append(perms.joinToString("、"))
                sb.append('\n')
            }
        if (packages.size > MAX_APPS) sb.append("- …（其余应用已省略）")
        return sb.toString().trim()
    }
}

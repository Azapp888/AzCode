package app.azcode.bridge.ime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Base64
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import app.azcode.bridge.DeviceControl
import app.azcode.bridge.PrivMode

/**
 * 内置 ADB 输入法的控制器：
 *  - 查询是否已启用 / 是否为当前输入法；
 *  - 借助 Shizuku / Root 直接执行 `ime enable` + `ime set` 完成启用与切换（普通权限无法写安全设置）；
 *  - 无 Shizuku / Root 时回退到系统「输入法设置」与输入法选择器，由用户手动启用；
 *  - 通过广播把 Unicode 文本发送给 [AdbInputMethodService]，写入当前输入框。
 */
object KeyboardController {

    /** Manifest 中声明服务时使用的相对类名，系统据此生成 IME id。 */
    private const val IME_CLASS_RELATIVE = ".ime.AdbInputMethodService"

    fun imeId(ctx: Context): String = "${ctx.packageName}/$IME_CLASS_RELATIVE"

    /** 部分系统可能使用完全限定类名，比较时两种写法都要兼容。 */
    private fun imeIds(ctx: Context): List<String> = listOf(
        imeId(ctx),
        "${ctx.packageName}/${ctx.packageName}${IME_CLASS_RELATIVE}",
    )

    fun componentName(ctx: Context): ComponentName =
        ComponentName(ctx.packageName, "${ctx.packageName}${IME_CLASS_RELATIVE}")

    /** 是否已在系统「已启用输入法」列表中。 */
    fun isEnabled(ctx: Context): Boolean {
        val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_INPUT_METHODS) ?: return false
        val ids = imeIds(ctx)
        return enabled.split(':').any { part -> ids.any { it.equals(part, ignoreCase = true) } }
    }

    /** 是否为当前默认输入法。 */
    fun isCurrent(ctx: Context): Boolean {
        val current = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: return false
        return imeIds(ctx).any { it.equals(current, ignoreCase = true) }
    }

    fun isReady(ctx: Context): Boolean = isEnabled(ctx) && isCurrent(ctx)

    /** 当前系统默认输入法 id，用于输入完成后的还原。 */
    fun currentImeId(ctx: Context): String? =
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)

    /**
     * 借助内置输入法向当前聚焦的输入框写入文本，完成后尽量还原用户原来的输入法。
     * 返回 null 表示成功；否则返回面向模型的失败原因。该方法会阻塞等待输入法切换，需在后台线程调用。
     */
    fun typeText(ctx: Context, text: String): String? {
        if (text.isEmpty()) return null
        val previous = currentImeId(ctx)
        val canRestore = hasSecureWrite(ctx)

        if (!isCurrent(ctx)) {
            if (!isEnabled(ctx)) {
                runCatching { DeviceControl.exec(ctx, "ime enable ${imeId(ctx)}") }
            }
            if (!isEnabled(ctx)) {
                return "内置输入法未启用，且当前无 Shizuku/Root 权限自动启用，请在系统输入法设置中手动启用「AzCode 输入法」。"
            }
            if (canRestore) runCatching { DeviceControl.exec(ctx, "ime set ${imeId(ctx)}") }
            if (!waitUntilCurrent(ctx)) {
                return "无法切换为内置输入法，请授予 Shizuku/Root 权限或手动在输入法切换器中选中「AzCode 输入法」。"
            }
        }

        val sent = commitText(ctx, text)
        // 等广播被输入法服务处理，再还原用户原来的输入法，避免打断用户后续手动输入。
        Thread.sleep(350)
        if (canRestore && !previous.isNullOrBlank() && previous != imeId(ctx)) {
            runCatching { DeviceControl.exec(ctx, "ime set $previous") }
        }
        return if (sent) null else "文本发送失败"
    }

    private fun waitUntilCurrent(ctx: Context, timeoutMs: Long = 1500): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isCurrent(ctx)) return true
            Thread.sleep(80)
        }
        return isCurrent(ctx)
    }

    /** 是否具备直接写安全设置的能力（Shizuku / Root）。 */
    private fun hasSecureWrite(ctx: Context): Boolean = when (DeviceControl.getMode(ctx)) {
        PrivMode.SHIZUKU -> DeviceControl.shizukuUsable()
        PrivMode.ROOT -> DeviceControl.rootAvailable()
        PrivMode.NORMAL -> DeviceControl.rootAvailable()
    }

    /**
     * 尝试启用并切换为内置 ADB 输入法。
     * 返回 true 表示已切换成功；false 表示需要用户到系统设置手动启用。
     */
    fun enableAndSwitch(ctx: Context): Boolean {
        if (!isEnabled(ctx) && hasSecureWrite(ctx)) {
            runCatching { DeviceControl.exec(ctx, "ime enable ${imeId(ctx)}") }
        }
        if (isEnabled(ctx)) switchTo(ctx)
        val ready = isReady(ctx)
        if (!ready) openInputMethodSettings(ctx)
        return ready
    }

    /** 已启用时切换为当前输入法；未启用时返回 false。 */
    fun switchTo(ctx: Context): Boolean {
        if (!isEnabled(ctx)) return false
        if (isCurrent(ctx)) return true
        // `ime set` 需要 Shizuku / Root；失败则调起系统输入法选择器。
        if (hasSecureWrite(ctx)) {
            runCatching { DeviceControl.exec(ctx, "ime set ${imeId(ctx)}") }
        }
        if (isCurrent(ctx)) return true
        runCatching {
            ctx.getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
        }
        return isCurrent(ctx)
    }

    fun openInputMethodSettings(ctx: Context) {
        runCatching {
            ctx.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /**
     * 确保可以写入。返回 null 表示就绪；否则返回面向模型的提示文本。
     */
    fun ensureWritable(ctx: Context): String? = when {
        !isEnabled(ctx) -> "内置 ADB 输入法尚未启用。请先在「设置 → 输入法设置」启用「AzCode 输入法」，或授予 Shizuku/Root 后由应用自动启用。"
        !isCurrent(ctx) -> if (switchTo(ctx)) null else "当前输入法不是「AzCode 输入法」，请在系统输入法切换器中选中后再试。"
        else -> null
    }

    /** 通过广播把文本写入当前输入框（Unicode 安全）。 */
    fun commitText(ctx: Context, text: String): Boolean {
        if (text.isEmpty()) return true
        val b64 = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return send(ctx, Intent(AdbInputMethodService.ACTION_INPUT_B64).putExtra("msg", b64))
    }

    fun sendKeyCode(ctx: Context, keyCode: Int): Boolean =
        send(ctx, Intent(AdbInputMethodService.ACTION_INPUT_CODE).putExtra("code", keyCode))

    fun clearText(ctx: Context): Boolean =
        send(ctx, Intent(AdbInputMethodService.ACTION_CLEAR_TEXT))

    fun editorAction(ctx: Context, action: Int): Boolean =
        send(ctx, Intent(AdbInputMethodService.ACTION_EDITOR_CODE).putExtra("code", action))

    private fun send(ctx: Context, intent: Intent): Boolean {
        intent.setPackage(ctx.packageName)
        ctx.sendBroadcast(intent)
        return true
    }

    fun toastIfNeeded(ctx: Context): Boolean {
        if (isReady(ctx)) return true
        Toast.makeText(ctx, "请先启用并切换到 AzCode 输入法", Toast.LENGTH_SHORT).show()
        enableAndSwitch(ctx)
        return false
    }

    /** 界面显示用的一行状态。 */
    fun statusText(ctx: Context): String = when {
        isReady(ctx) -> "已启用并正在使用"
        isEnabled(ctx) -> "已启用，未切换"
        else -> "未启用"
    }
}

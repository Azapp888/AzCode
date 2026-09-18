package app.azcode.bridge

import android.app.Application
import com.google.android.material.color.DynamicColors

/**
 * 应用入口。
 *
 * Android 12+ 启用 Material You 动态取色：系统与 Material 控件的取色会跟随壁纸，
 * 而各界面中以 @color/primary 等静态引用的品牌色保持不变，从而兼顾品牌一致性与系统融合。
 * Android 12 以下自动回退到 DeepSeek 静态配色（values / values-night）。
 */
class AzCodeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        runCatching { DynamicColors.applyToActivitiesIfAvailable(this) }
    }
}

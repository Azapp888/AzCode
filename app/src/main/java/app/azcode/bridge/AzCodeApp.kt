package app.azcode.bridge

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.google.android.material.color.DynamicColors

/**
 * 应用入口。
 *
 * Android 12+ 启用 Material You 动态取色：系统与 Material 控件的取色会跟随壁纸，
 * 而各界面中以 @color/primary 等静态引用的品牌色保持不变，从而兼顾品牌一致性与系统融合。
 * Android 12 以下自动回退到 DeepSeek 静态配色（values / values-night）。
 *
 * 同时跟踪应用自身界面的前后台状态：主界面在前台时，AI 操作光效的左下角悬浮气泡不显示，
 * 退出应用到其他界面后才显示（气泡内容仍由无障碍服务持续更新）。
 */
class AzCodeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        runCatching { DynamicColors.applyToActivitiesIfAvailable(this) }
        registerActivityLifecycleCallbacks(lifecycleCallbacks)
    }

    /** 已 start 的 Activity 数量；>0 表示应用自身界面可见。Activity 间切换时不会归零。 */
    private var startedActivities = 0

    private val lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            startedActivities++
            if (startedActivities == 1) updateForeground(true)
        }

        override fun onActivityStopped(activity: Activity) {
            if (startedActivities == 0) return
            startedActivities--
            if (startedActivities == 0) updateForeground(false)
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    private fun updateForeground(foreground: Boolean) {
        isInForeground = foreground
        // 通知无障碍服务按前台状态重新决定左下角气泡的显隐。
        AzAccessibilityService.onAppForegroundChanged()
    }

    companion object {
        /** 应用自身界面是否处于前台。前台时左下角悬浮气泡保持隐藏。 */
        @Volatile
        var isInForeground: Boolean = false
            private set
    }
}

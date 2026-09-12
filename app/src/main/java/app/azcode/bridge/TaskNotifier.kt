package app.azcode.bridge

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * 任务结束通知：任务完成 / 停止 / 出错时在通知栏提醒。
 * 单独使用一条普通优先级渠道，与桥接前台服务渠道区分。
 */
object TaskNotifier {

    private const val CHANNEL_ID = "azcode_task"
    private const val NOTIF_ID = 8849
    private const val NOTIF_QUESTION_ID = 8850

    fun ensureChannel(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_task),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        mgr.createNotificationChannel(channel)
    }

    fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return true
    }

    /** 任务结束提醒；[success] 为 true 表示正常完成。 */
    fun notifyFinished(context: Context, sessionTitle: String, summary: String?, success: Boolean) {
        if (!canNotify(context)) return
        ensureChannel(context)

        val title = sessionTitle.ifBlank { context.getString(R.string.session_default_title) }
        val contentTitle = context.getString(
            if (success) R.string.notif_task_done else R.string.notif_task_stopped,
        )
        val contentText = summary?.takeIf { it.isNotBlank() } ?: title

        val pending = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            ),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setStyle(Notification.BigTextStyle().bigText("$title\n$contentText"))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        context.getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, notification)
    }

    /** Agent 需要用户做选择时提醒，点击回到应用查看弹窗。 */
    fun notifyQuestion(context: Context, question: String) {
        if (!canNotify(context)) return
        ensureChannel(context)

        val pending = PendingIntent.getActivity(
            context, 1,
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            ),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(context.getString(R.string.notif_question))
            .setContentText(question)
            .setStyle(Notification.BigTextStyle().bigText(question))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        context.getSystemService(NotificationManager::class.java)?.notify(NOTIF_QUESTION_ID, notification)
    }
}

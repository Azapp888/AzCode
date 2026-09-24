package app.azcode.bridge

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build

/**
 * 通知中心：任务结束提醒 + Agent 提问提醒。
 *
 * 设计要点：
 *  - 使用高优先级渠道（横幅 + 铃声 + 震动），用户授予通知权限后即可获得弹窗提醒；
 *  - 通知显示应用图标（大图标），状态栏用小图标；
 *  - 提问通知为常驻通知（ongoing，不自动收回），展开后可直接点选选项或输入文本作答。
 */
object TaskNotifier {

    private const val CHANNEL_TASK = "azcode_task_v2"
    private const val CHANNEL_QUESTION = "azcode_question_v2"
    private const val NOTIF_ID = 8849
    private const val NOTIF_QUESTION_ID = 8850

    /** 高优先级渠道：横幅 + 铃声 + 震动。旧渠道 id 保留不动，避免影响用户既有设置。 */
    fun ensureChannel(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_TASK) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_TASK,
                    context.getString(R.string.notif_channel_task),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.notif_channel_task_desc)
                    enableVibration(true)
                    setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .build(),
                    )
                },
            )
        }
        if (mgr.getNotificationChannel(CHANNEL_QUESTION) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_QUESTION,
                    context.getString(R.string.notif_channel_question),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.notif_channel_question_desc)
                    enableVibration(true)
                    setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .build(),
                    )
                },
            )
        }
    }

    fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return false
        return mgr.areNotificationsEnabled()
    }

    /** 应用图标，用于通知大图标展示品牌 logo。 */
    private fun appIcon(context: Context) = runCatching {
        BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)
    }.getOrNull()

    private fun openAppIntent(context: Context, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context, requestCode,
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            ),
            PendingIntent.FLAG_IMMUTABLE,
        )

    /** 任务结束提醒；[success] 为 true 表示正常完成。 */
    fun notifyFinished(context: Context, sessionTitle: String, summary: String?, success: Boolean) {
        if (!canNotify(context)) return
        ensureChannel(context)

        val title = sessionTitle.ifBlank { context.getString(R.string.session_default_title) }
        val contentTitle = context.getString(
            if (success) R.string.notif_task_done else R.string.notif_task_stopped,
        )
        val contentText = summary?.takeIf { it.isNotBlank() } ?: title

        val builder = Notification.Builder(context, CHANNEL_TASK)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setStyle(Notification.BigTextStyle().bigText("$title\n$contentText"))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context, 0))
        appIcon(context)?.let { builder.setLargeIcon(it) }

        context.getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, builder.build())
    }

    /**
     * Agent 提问提醒：常驻通知，展开后可点选选项或直接输入文本作答。
     * 作答后由 [QuestionActionReceiver] 收起通知并把答案回传给 Agent。
     */
    fun notifyQuestion(context: Context, question: AgentQuestion) {
        if (!canNotify(context)) return
        ensureChannel(context)

        val openApp = openAppIntent(context, 1)
        val builder = Notification.Builder(context, CHANNEL_QUESTION)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(context.getString(R.string.notif_question))
            .setContentText(question.question)
            .setStyle(Notification.BigTextStyle().bigText(buildBigText(context, question)))
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(openApp)
        appIcon(context)?.let { builder.setLargeIcon(it) }

        // 选项按钮（通知展开后可见），最多 3 个。
        question.options.take(3).forEachIndexed { index, option ->
            val pi = PendingIntent.getBroadcast(
                context, 200 + index,
                Intent(context, QuestionActionReceiver::class.java)
                    .setAction(ACTION_QUESTION)
                    .putExtra(QuestionActionReceiver.EXTRA_ANSWER, option),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_stat),
                    option.take(40),
                    pi,
                ).build(),
            )
        }

        // 手动输入：RemoteInput 直接在通知内展开一个输入框。
        if (question.allowCustom) {
            val remoteInput = RemoteInput.Builder(QuestionActionReceiver.REMOTE_INPUT_KEY)
                .setLabel(context.getString(R.string.notif_question_input_hint))
                .build()
            val replyPi = PendingIntent.getBroadcast(
                context, 260,
                Intent(context, QuestionActionReceiver::class.java).setAction(ACTION_QUESTION),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_stat),
                    context.getString(R.string.notif_question_send),
                    replyPi,
                ).addRemoteInput(remoteInput).build(),
            )
        }

        builder.addAction(
            Notification.Action.Builder(
                Icon.createWithResource(context, R.drawable.ic_stat),
                context.getString(R.string.notif_question_open),
                openApp,
            ).build(),
        )

        context.getSystemService(NotificationManager::class.java)?.notify(NOTIF_QUESTION_ID, builder.build())
    }

    fun cancelQuestion(context: Context) {
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_QUESTION_ID)
        }
    }

    private fun buildBigText(context: Context, question: AgentQuestion): String {
        val sb = StringBuilder()
        if (question.total > 1) {
            sb.append(context.getString(R.string.question_progress, question.index, question.total)).append(' ')
        }
        sb.append(question.question)
        if (question.options.isNotEmpty()) {
            sb.append('\n')
            question.options.forEach { sb.append("\n· ").append(it) }
            if (question.allowCustom) sb.append("\n\n").append(context.getString(R.string.notif_question_extra_hint))
        }
        return sb.toString()
    }

    const val ACTION_QUESTION = "app.azcode.bridge.QUESTION_ACTION"
}

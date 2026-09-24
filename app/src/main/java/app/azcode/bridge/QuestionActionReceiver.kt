package app.azcode.bridge

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * 通知栏问答的操作入口：
 *  - 点选选项：答案放在 [EXTRA_ANSWER]；
 *  - 手动输入：答案放在 RemoteInput 的 [REMOTE_INPUT_KEY] 中。
 *
 * 收到后交给 [QuestionBus] 完成正在等待的提问，并收起通知。若当前没有等待中的提问
 * （例如进程已被重建），则打开应用让用户回到聊天界面。
 */
class QuestionActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val answer = extractAnswer(intent)?.trim().orEmpty()
        if (answer.isEmpty()) {
            TaskNotifier.cancelQuestion(context)
            return
        }

        val delivered = QuestionBus.submit(answer)
        TaskNotifier.cancelQuestion(context)

        if (!delivered) {
            runCatching {
                context.startActivity(
                    Intent(context, MainActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP,
                    ),
                )
            }
            Toast.makeText(context, context.getString(R.string.notif_question_late), Toast.LENGTH_SHORT).show()
        }
    }

    private fun extractAnswer(intent: Intent): String? {
        intent.getStringExtra(EXTRA_ANSWER)?.let { if (it.isNotBlank()) return it }
        val results = RemoteInput.getResultsFromIntent(intent) ?: return null
        return results.getCharSequence(REMOTE_INPUT_KEY)?.toString()
    }

    companion object {
        const val EXTRA_ANSWER = "azcode_answer"
        const val REMOTE_INPUT_KEY = "azcode_answer_input"
    }
}

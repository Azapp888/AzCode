package app.azcode.bridge

/**
 * 当前待作答问题的回传通道。
 *
 * Agent 提问时主线程会阻塞等待；用户既可以在应用内的问答区作答，也可以直接展开通知栏
 * 点选选项或输入文本。通知栏操作经 [QuestionActionReceiver] 投递到本对象，再交给正在
 * 等待的那次提问，二者共用同一份答案。
 */
object QuestionBus {

    @Volatile
    private var handler: ((String) -> Unit)? = null

    /** Agent 每次提问前登记回调；重复登记以最新一次为准（提问是串行的）。 */
    fun register(callback: (String) -> Unit) {
        handler = callback
    }

    fun clear() {
        handler = null
    }

    /** 提交一个答案；返回 false 表示当前没有等待作答的提问。 */
    fun submit(answer: String): Boolean {
        val h = handler ?: return false
        if (answer.isBlank()) return false
        h(answer)
        return true
    }
}

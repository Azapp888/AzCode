package app.azcode.bridge

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatEditText

/**
 * 长按直接进入语音输入的自定义输入框。
 *
 * 系统 EditText 长按默认进入文本选择，可能吞掉 OnLongClickListener；这里重写 performLongClick，
 * 让长按优先触发语音回调，返回 true 时不再进入选择模式。返回 false 时保留系统默认选择行为。
 */
class PressToTalkEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatEditText(context, attrs) {

    var onLongPressVoice: (() -> Boolean)? = null

    override fun performLongClick(): Boolean {
        val handler = onLongPressVoice
        if (handler != null && handler()) return true
        return super.performLongClick()
    }
}
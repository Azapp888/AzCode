package app.azcode.bridge

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/**
 * 高度受限的 [ScrollView]：内容较短时自适应高度，超过上限时可纵向滚动。
 * 用于「ask question for user」面板展示可能很长的公式/问卷，避免撑破底部输入区。
 */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ScrollView(context, attrs, defStyleAttr) {

    var maxHeightPx: Int = (280 * resources.displayMetrics.density).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val spec = MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
        super.onMeasure(widthMeasureSpec, spec)
    }
}

package app.azcode.bridge.ime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.util.Base64
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

/**
 * 内置 ADB 输入法：不显示真实按键，只接收广播指令后把文本提交到当前输入框，
 * 用于让 Agent 向任意应用的输入框写入 Unicode 文本（Android 自带的 `input text`
 * 无法输入中文等非 ASCII 字符）。
 *
 * 指令集与开源项目 senzhk/ADBKeyBoard（MIT）保持一致，便于用 adb 直接调用：
 *  - ADB_INPUT_TEXT    msg=<utf8 文本>
 *  - ADB_INPUT_B64     msg=<utf8 文本的 base64>（推荐，规避 am 对非 ASCII 的限制）
 *  - ADB_INPUT_CHARS   chars=<int 数组>
 *  - ADB_INPUT_CODE    code=<KeyEvent 键码>
 *  - ADB_EDITOR_CODE   code=<EditorInfo action>
 *  - ADB_CLEAR_TEXT
 *  - ADB_ACTION_SEARCH / GO / DONE / NEXT / SEND
 *
 * 由 [KeyboardController] 负责启用、切换与发送指令。
 */
class AdbInputMethodService : InputMethodService() {

    private var receiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        registerAdbReceiver()
    }

    private fun registerAdbReceiver() {
        if (receiver != null) return
        val filter = IntentFilter().apply {
            addAction(ACTION_INPUT_TEXT)
            addAction(ACTION_INPUT_B64)
            addAction(ACTION_INPUT_CHARS)
            addAction(ACTION_INPUT_CODE)
            addAction(ACTION_EDITOR_CODE)
            addAction(ACTION_CLEAR_TEXT)
            addAction(ACTION_SEARCH)
            addAction(ACTION_GO)
            addAction(ACTION_DONE)
            addAction(ACTION_NEXT)
            addAction(ACTION_SEND)
        }
        val r = AdbReceiver()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // 仅接收本应用（Agent 进程）发出的指令，避免其它应用向当前输入框注入文本。
            registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(r, filter)
        }
        receiver = r
    }

    override fun onCreateInputView(): View =
        layoutInflater.inflate(R.layout.view_adb_ime, null)

    override fun onDestroy() {
        receiver?.let { runCatching { unregisterReceiver(it) } }
        receiver = null
        super.onDestroy()
    }

    private fun commit(text: CharSequence) {
        currentInputConnection?.commitText(text, 1)
    }

    private inner class AdbReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_INPUT_TEXT -> {
                    intent.getStringExtra("msg")?.let { commit(it) }
                    intent.getStringExtra("mcode")?.let { sendMetaCodes(it) }
                }
                ACTION_INPUT_B64 -> {
                    val data = intent.getStringExtra("msg") ?: return
                    val text = runCatching {
                        String(Base64.decode(data, Base64.DEFAULT), Charsets.UTF_8)
                    }.getOrNull() ?: return
                    commit(text)
                }
                ACTION_INPUT_CHARS -> {
                    intent.getIntArrayExtra("chars")?.let { commit(String(it, 0, it.size)) }
                }
                ACTION_INPUT_CODE -> {
                    val code = intent.getIntExtra("code", -1)
                    if (code != -1) currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                }
                ACTION_EDITOR_CODE -> {
                    val code = intent.getIntExtra("code", -1)
                    if (code != -1) currentInputConnection?.performEditorAction(code)
                }
                ACTION_CLEAR_TEXT -> clearText()
                ACTION_SEARCH -> currentInputConnection?.performEditorAction(EditorInfo.IME_ACTION_SEARCH)
                ACTION_GO -> currentInputConnection?.performEditorAction(EditorInfo.IME_ACTION_GO)
                ACTION_DONE -> currentInputConnection?.performEditorAction(EditorInfo.IME_ACTION_DONE)
                ACTION_NEXT -> currentInputConnection?.performEditorAction(EditorInfo.IME_ACTION_NEXT)
                ACTION_SEND -> currentInputConnection?.performEditorAction(EditorInfo.IME_ACTION_SEND)
                else -> Log.w(TAG, "unknown action: ${intent.action}")
            }
        }
    }

    private fun clearText() {
        val ic = currentInputConnection ?: return
        val req = ExtractedTextRequest().apply {
            hintMaxChars = 100_000
            hintMaxLines = 10_000
        }
        val et: ExtractedText? = ic.getExtractedText(req, 0)
        if (et?.text != null) {
            val before = ic.getTextBeforeCursor(et.text.length, 0)
            val after = ic.getTextAfterCursor(et.text.length, 0)
            if (before != null && after != null) ic.deleteSurroundingText(before.length, after.length)
        } else {
            ic.performContextMenuAction(android.R.id.selectAll)
            ic.commitText("", 1)
        }
    }

    /** 支持 `mcode='4096+8192,29'` 形式的组合键（对应 ADBKeyBoard 的 meta 指令）。 */
    private fun sendMetaCodes(spec: String) {
        val ic = currentInputConnection ?: return
        val parts = spec.split(",")
        var i = 0
        while (i < parts.size - 1) {
            val meta = parts[i].split("+").mapNotNull { it.trim().toIntOrNull() }
            val keyCode = parts[i + 1].trim().toIntOrNull()
            if (keyCode != null) {
                val metaState = if (meta.isEmpty()) 0 else meta.reduce { a, b -> a or b }
                ic.sendKeyEvent(
                    KeyEvent(
                        0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, metaState,
                        0, 0,
                        KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE,
                        InputDevice.SOURCE_KEYBOARD,
                    ),
                )
            }
            i += 2
        }
    }

    companion object {
        private const val TAG = "AdbIME"

        const val ACTION_INPUT_TEXT = "ADB_INPUT_TEXT"
        const val ACTION_INPUT_B64 = "ADB_INPUT_B64"
        const val ACTION_INPUT_CHARS = "ADB_INPUT_CHARS"
        const val ACTION_INPUT_CODE = "ADB_INPUT_CODE"
        const val ACTION_EDITOR_CODE = "ADB_EDITOR_CODE"
        const val ACTION_CLEAR_TEXT = "ADB_CLEAR_TEXT"
        const val ACTION_SEARCH = "ADB_ACTION_SEARCH"
        const val ACTION_GO = "ADB_ACTION_GO"
        const val ACTION_DONE = "ADB_ACTION_DONE"
        const val ACTION_NEXT = "ADB_ACTION_NEXT"
        const val ACTION_SEND = "ADB_ACTION_SEND"
    }
}

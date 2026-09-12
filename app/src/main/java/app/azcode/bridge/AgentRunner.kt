package app.azcode.bridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 设备端 Agent 决策循环：读取屏幕 → 交 DeepSeek 决策 → 直接调用本机无障碍/Shizuku 执行 → 回灌结果。
 * 与 Windows 端 AgentRunner 工具集一致，区别是这里直接在本机执行，无需 adb/HTTP。
 */
class AgentRunner(
    private val ctx: Context,
    private val log: (String) -> Unit,
) {
    @Volatile private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    fun run(task: String) {
        val apiKey = AgentConfig.apiKey(ctx)
        val baseUrl = AgentConfig.baseUrl(ctx)
        val model = AgentConfig.model(ctx)
        val maxSteps = AgentConfig.maxSteps(ctx)

        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
            put(JSONObject().put("role", "user").put("content", task))
        }
        val tools = buildTools()

        for (step in 1..maxSteps) {
            if (cancelled) { log("已停止。"); return }
            log("[step $step/$maxSteps] 请求模型…")
            val reply = DeepSeekClient.chat(baseUrl, apiKey, model, messages, tools)

            val assistant = JSONObject().apply {
                put("role", "assistant")
                put("content", reply.content ?: JSONObject.NULL)
                if (reply.toolCalls.isNotEmpty()) {
                    put("tool_calls", JSONArray().apply {
                        reply.toolCalls.forEach { c ->
                            put(JSONObject().apply {
                                put("id", c.id)
                                put("type", "function")
                                put("function", JSONObject()
                                    .put("name", c.name)
                                    .put("arguments", c.arguments))
                            })
                        }
                    })
                }
            }
            messages.put(assistant)

            if (reply.toolCalls.isEmpty()) {
                log("完成：${reply.content ?: "(无内容)"}")
                return
            }

            for (c in reply.toolCalls) {
                if (cancelled) { log("已停止。"); return }
                val result = execute(c)
                log("  ${c.name} -> ${result.take(400)}")
                messages.put(JSONObject().apply {
                    put("role", "tool")
                    put("tool_call_id", c.id)
                    put("content", result)
                })
            }
        }
        log("达到最大步数 $maxSteps，停止。")
    }

    private fun execute(call: ToolCall): String = try {
        val args = runCatching { JSONObject(call.arguments.ifBlank { "{}" }) }.getOrElse { JSONObject() }
        when (call.name) {
            "get_screen" -> AzAccessibilityService.instance?.dumpScreenJson()
                ?: err("无障碍服务未开启，请在系统设置开启 AzCode Screen Control")

            "tap" -> {
                val svc = AzAccessibilityService.instance ?: error("无障碍服务未开启")
                when {
                    args.has("text") ->
                        if (svc.tapText(args.getString("text"))) ok() else err("未找到文本：${args.getString("text")}")
                    args.has("x") && args.has("y") ->
                        if (svc.dispatchTap(args.getDouble("x").toFloat(), args.getDouble("y").toFloat())) ok()
                        else err("点击派发失败")
                    else -> err("需要 text 或 x/y")
                }
            }

            "swipe" -> {
                val svc = AzAccessibilityService.instance ?: error("无障碍服务未开启")
                val okSwipe = svc.dispatchSwipe(
                    args.getDouble("x1").toFloat(),
                    args.getDouble("y1").toFloat(),
                    args.getDouble("x2").toFloat(),
                    args.getDouble("y2").toFloat(),
                    args.optLong("duration", 300),
                )
                if (okSwipe) ok() else err("滑动派发失败")
            }

            "global" -> {
                val svc = AzAccessibilityService.instance ?: error("无障碍服务未开启")
                if (svc.globalAction(args.optString("action"))) ok() else err("未知动作")
            }

            "shell" -> {
                val cmd = args.optString("cmd")
                if (cmd.isBlank()) err("缺少 cmd")
                else JSONObject().put("ok", true).put("output", DeviceControl.exec(ctx, cmd)).toString()
            }

            "finish" -> JSONObject().put("ok", true).put("summary", args.optString("summary")).toString()

            else -> err("未知工具 ${call.name}")
        }
    } catch (e: Exception) {
        err(e.message ?: "执行异常")
    }

    private fun ok() = """{"ok":true}"""

    private fun err(message: String) = JSONObject().put("ok", false).put("error", message).toString()

    private fun buildTools(): JSONArray {
        fun fn(name: String, desc: String, props: JSONObject, required: List<String>): JSONObject = JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", name)
                put("description", desc)
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", props)
                    put("required", JSONArray(required))
                })
            })
        }
        fun num(desc: String) = JSONObject().put("type", "number").put("description", desc)
        fun str(desc: String) = JSONObject().put("type", "string").put("description", desc)

        return JSONArray().apply {
            put(fn("get_screen", "读取当前屏幕可见节点（文本、坐标、可点击性）。", JSONObject(), emptyList()))
            put(fn("tap", "点击屏幕，可用坐标或文本二者之一。", JSONObject()
                .put("x", num("X 物理像素"))
                .put("y", num("Y 物理像素"))
                .put("text", str("要点击的文本")), emptyList()))
            put(fn("swipe", "从 (x1,y1) 滑动到 (x2,y2)。", JSONObject()
                .put("x1", num("起点 X"))
                .put("y1", num("起点 Y"))
                .put("x2", num("终点 X"))
                .put("y2", num("终点 Y"))
                .put("duration", num("持续毫秒，默认 300")), listOf("x1", "y1", "x2", "y2")))
            put(fn("global", "系统导航动作。", JSONObject()
                .put("action", JSONObject()
                    .put("type", "string")
                    .put("enum", JSONArray(listOf("back", "home", "recents", "notifications")))
                    .put("description", "导航动作")), listOf("action")))
            put(fn("shell", "以 Shizuku/Root 身份执行 shell 命令（需高权限模式）。", JSONObject()
                .put("cmd", str("要执行的命令")), listOf("cmd")))
            put(fn("finish", "任务结束并给出总结。", JSONObject()
                .put("summary", str("结果总结")), listOf("summary")))
        }
    }

    companion object {
        private const val SYSTEM_PROMPT = """你是 AzCode，一个运行在 Android 手机本地的自动化助手。
每一步先调用 get_screen 观察当前界面，再选择动作。坐标使用屏幕物理像素。
优先按文本点击（tap 的 text 字段）以提高鲁棒性；无法定位文本时再用坐标。
执行 shell 前确认任务确实需要；NORMAL 模式下 shell 会失败，此时改用无障碍能力。
任务完成或无法继续时，调用 finish 并给出简短总结。"""
    }
}

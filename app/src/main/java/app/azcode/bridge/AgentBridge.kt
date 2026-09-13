package app.azcode.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * Agent 能力桥：监听 127.0.0.1:[PORT]，把设备能力暴露给本机/经 adb forward 的 Agent。
 *
 * 路由：
 *   GET  /health            → 能力探测 JSON
 *   GET  /screen            → 屏幕节点 JSON（需无障碍）
 *   POST /tap               → {"x":..,"y":..} 或 {"text":".."}
 *   POST /swipe             → {"x1":..,"y1":..,"x2":..,"y2":..,"duration":300}
 *   POST /global            → {"action":"back|home|recents|notifications"}
 *   POST /shell             → {"cmd":".."}（需 Shizuku/Root 模式）
 *   POST /notify            → {"title":"..","body":".."}
 *   POST /agent             → {"task":"..","session":"可选会话 id"} 运行完整 Agent 任务，返回事件
 *
 * ponytail: 仅绑定环回、无鉴权。跨机访问必须经 `adb forward`；若将来暴露到局域网再加 token。
 */
object AgentBridge {

    private const val TAG = "AgentBridge"
    const val PORT = 8848
    private const val CHANNEL_ID = "azcode_bridge"

    @Volatile private var server: ServerSocket? = null
    @Volatile private var thread: Thread? = null

    val isRunning: Boolean get() = server != null

    fun start(ctx: Context) {
        if (server != null) return
        try {
            val ss = ServerSocket(PORT, 16, InetAddress.getByName("127.0.0.1"))
            server = ss
            thread = Thread({
                while (!ss.isClosed) {
                    try {
                        handle(ctx.applicationContext, ss.accept())
                    } catch (e: Exception) {
                        if (!ss.isClosed) Log.w(TAG, "accept: ${e.message}")
                    }
                }
            }, "azcode-bridge").apply { isDaemon = true; start() }
            Log.i(TAG, "bridge started on 127.0.0.1:$PORT")
        } catch (e: Exception) {
            Log.w(TAG, "start failed: ${e.message}")
        }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        thread = null
        Log.i(TAG, "bridge stopped")
    }

    private fun handle(ctx: Context, client: Socket) {
        Thread({
            try {
                client.soTimeout = 15_000
                val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
                val requestLine = reader.readLine() ?: return@Thread
                val parts = requestLine.split(" ")
                if (parts.size < 2) return@Thread
                val method = parts[0]
                val path = parts[1].substringBefore('?')

                var contentLength = 0
                var line = reader.readLine()
                while (line != null && line.isNotEmpty()) {
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                    line = reader.readLine()
                }
                val body = if (contentLength > 0) {
                    val buf = CharArray(contentLength)
                    var n = 0
                    while (n < contentLength) {
                        val r = reader.read(buf, n, contentLength - n)
                        if (r < 0) break
                        n += r
                    }
                    String(buf, 0, n)
                } else ""

                val (status, json) = route(ctx, method, path, body)
                respond(client, status, json)
            } catch (e: Exception) {
                Log.w(TAG, "handle: ${e.message}")
                runCatching { respond(client, 500, """{"ok":false,"error":"${e.message}"}""") }
            } finally {
                runCatching { client.close() }
            }
        }, "azcode-bridge-req").apply { isDaemon = true; start() }
    }

    private fun route(ctx: Context, method: String, path: String, body: String): Pair<Int, String> = when {
        method == "GET" && path == "/health" -> 200 to health(ctx)
        method == "GET" && path == "/screen" -> screen()
        method == "POST" && path == "/tap" -> tap(body)
        method == "POST" && path == "/swipe" -> swipe(body)
        method == "POST" && path == "/global" -> global(body)
        method == "POST" && path == "/shell" -> shell(ctx, body)
        method == "POST" && path == "/notify" -> notify(ctx, body)
        method == "POST" && path == "/agent" -> agent(ctx, body)
        else -> 404 to """{"ok":false,"error":"unknown route"}"""
    }

    /** 通过 Listen CLI 运行一次完整 Agent 任务，逐条收集事件后一次性返回。 */
    private fun agent(ctx: Context, body: String): Pair<Int, String> {
        return try {
            val obj = JSONObject(body)
            val task = obj.optString("task").trim()
            if (task.isBlank()) return 400 to """{"ok":false,"error":"missing task"}"""

            val events = JSONArray()
            val answer = StringBuilder()

            val listener: (AgentEvent) -> Unit = { e ->
                when (e) {
                    is AgentEvent.Thinking -> events.put(JSONObject().put("type", "thinking"))
                    is AgentEvent.AssistantText -> {
                        answer.append(e.text).append("\n")
                        events.put(JSONObject().put("type", "assistant").put("text", e.text))
                    }
                    is AgentEvent.ToolStart -> events.put(
                        JSONObject().put("type", "tool_start").put("name", e.name).put("args", e.args)
                    )
                    is AgentEvent.ToolResult -> events.put(
                        JSONObject().put("type", "tool_result").put("name", e.name)
                            .put("ok", e.ok).put("output", e.output.take(2000))
                    )
                    is AgentEvent.Images -> events.put(
                        JSONObject().put("type", "images").put("urls", JSONArray(e.urls))
                    )
                    is AgentEvent.Notice -> events.put(JSONObject().put("type", "notice").put("text", e.text))
                    is AgentEvent.Failure -> events.put(JSONObject().put("type", "failure").put("text", e.message))
                }
            }

            val session = obj.optString("session").takeIf { it.isNotBlank() }
                ?.let { SessionStore.get(ctx, it) }
                ?: SessionStore.create(ctx, SessionStore.deriveTitle(task))

            AgentRunner(
                ctx = ctx,
                askUser = { q ->
                    // 命令行非交互：若模型提问则记录并返回 null，提示用户改用 App 界面作答。
                    events.put(JSONObject().put("type", "question").put("text", q.question))
                    null
                },
                listener = listener,
            ).run(task, emptyList(), session)

            SessionStore.save(ctx, session)
            JSONObject().apply {
                put("ok", true)
                put("session", session.id)
                put("answer", answer.toString().trim())
                put("events", events)
            }.toString().let { 200 to it }
        } catch (e: Exception) {
            500 to JSONObject().put("ok", false).put("error", e.message ?: "agent failed").toString()
        }
    }

    private fun health(ctx: Context): String = JSONObject().apply {
        put("ok", true)
        put("app", "azcode-bridge")
        put("port", PORT)
        put("accessibility", AzAccessibilityService.isEnabled())
        put("shizuku", DeviceControl.shizukuUsable())
        put("root", DeviceControl.rootAvailable())
        put("mode", DeviceControl.getMode(ctx).name)
    }.toString()

    private fun screen(): Pair<Int, String> {
        val svc = AzAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
        return try {
            200 to svc.dumpScreenJson()
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"${e.message}"}"""
        }
    }

    private fun tap(body: String): Pair<Int, String> {
        val svc = AzAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
        return try {
            val obj = JSONObject(body)
            val ok = when {
                obj.has("text") -> svc.tapText(obj.getString("text"))
                obj.has("x") && obj.has("y") ->
                    svc.dispatchTap(obj.getDouble("x").toFloat(), obj.getDouble("y").toFloat())
                else -> false
            }
            if (ok) 200 to """{"ok":true}""" else 500 to """{"ok":false,"error":"tap failed / text not found"}"""
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"${e.message}"}"""
        }
    }

    private fun swipe(body: String): Pair<Int, String> {
        val svc = AzAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
        return try {
            val obj = JSONObject(body)
            val ok = svc.dispatchSwipe(
                obj.getDouble("x1").toFloat(),
                obj.getDouble("y1").toFloat(),
                obj.getDouble("x2").toFloat(),
                obj.getDouble("y2").toFloat(),
                obj.optLong("duration", 300),
            )
            if (ok) 200 to """{"ok":true}""" else 500 to """{"ok":false,"error":"swipe failed"}"""
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"${e.message}"}"""
        }
    }

    private fun global(body: String): Pair<Int, String> {
        val svc = AzAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
        return try {
            val action = JSONObject(body).optString("action")
            if (svc.globalAction(action)) 200 to """{"ok":true}""" else 400 to """{"ok":false,"error":"unknown action"}"""
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"${e.message}"}"""
        }
    }

    private fun shell(ctx: Context, body: String): Pair<Int, String> {
        return try {
            val cmd = JSONObject(body).optString("cmd")
            if (cmd.isBlank()) return 400 to """{"ok":false,"error":"missing cmd"}"""
            val output = DeviceControl.exec(ctx, cmd)
            JSONObject().put("ok", true).put("output", output).toString().let { 200 to it }
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"${e.message}"}"""
        }
    }

    private fun notify(ctx: Context, body: String): Pair<Int, String> {
        return try {
            val obj = JSONObject(body)
            val title = obj.optString("title").ifEmpty { "AzCode" }
            val text = obj.optString("body").ifEmpty { "任务已完成" }
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "AzCode 通知", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return 403 to """{"ok":false,"error":"notification permission not granted"}"""
            }
            val builder = if (Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(ctx, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION") Notification.Builder(ctx)
            }
            builder.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
            nm.notify(System.currentTimeMillis().toInt() and 0x7FFFFFFF, builder.build())
            200 to """{"ok":true}"""
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"${e.message}"}"""
        }
    }

    private fun respond(client: Socket, status: Int, json: String) {
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        val head = "HTTP/1.1 $status OK\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        client.getOutputStream().apply {
            write(head.toByteArray(StandardCharsets.UTF_8))
            write(bytes)
            flush()
        }
    }
}

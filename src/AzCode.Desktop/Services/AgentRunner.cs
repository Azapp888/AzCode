using System.Text.Json.Nodes;
using AzCode.Desktop.Models;

namespace AzCode.Desktop.Services;

/// <summary>
/// Agent 决策循环：读取屏幕 → 交给 DeepSeek 决策 → 经能力桥执行 → 回灌结果，直至结束或达最大步数。
/// </summary>
public sealed class AgentRunner
{
    private readonly AppConfig _cfg;
    private readonly DeviceBridgeClient _bridge;
    private readonly DeepSeekClient _llm = new();

    public event Action<string>? Log;

    public AgentRunner(AppConfig cfg, DeviceBridgeClient bridge)
    {
        _cfg = cfg;
        _bridge = bridge;
    }

    private const string SystemPrompt = """
        你是 AzCode，一个通过 HTTP 能力桥控制 Android 手机的自动化助手。
        每一步先调用 get_screen 观察当前界面，再选择动作。坐标使用屏幕物理像素。
        优先按文本点击（tap 的 text 字段）以提高鲁棒性；无法定位文本时再用坐标。
        执行 shell 前确认任务确实需要；NORMAL 模式下 shell 会失败，此时改用无障碍能力。
        任务完成或无法继续时，调用 finish 并给出简短总结。
        """;

    public async Task RunAsync(string task, CancellationToken ct)
    {
        var messages = new List<ChatMessage>
        {
            new() { Role = "system", Content = SystemPrompt },
            new() { Role = "user", Content = task },
        };
        var tools = BuildTools();

        for (var step = 1; step <= _cfg.MaxSteps; step++)
        {
            ct.ThrowIfCancellationRequested();
            Log?.Invoke($"[step {step}/{_cfg.MaxSteps}] 请求模型…");

            var msg = await _llm.ChatAsync(_cfg, messages, tools, ct);
            messages.Add(msg);

            if (msg.ToolCalls is null || msg.ToolCalls.Count == 0)
            {
                Log?.Invoke($"完成：{msg.Content}");
                return;
            }

            foreach (var call in msg.ToolCalls)
            {
                ct.ThrowIfCancellationRequested();
                var result = await ExecuteAsync(call, ct);
                Log?.Invoke($"  {call.Function.Name} -> {Truncate(result, 500)}");
                messages.Add(new ChatMessage
                {
                    Role = "tool",
                    ToolCallId = call.Id,
                    Content = result,
                });
            }
        }

        Log?.Invoke($"达到最大步数 {_cfg.MaxSteps}，停止。");
    }

    private async Task<string> ExecuteAsync(ToolCall call, CancellationToken ct)
    {
        var args = ParseArgs(call.Function.Arguments);
        try
        {
            return call.Function.Name switch
            {
                "get_screen" => await _bridge.GetAsync("/screen"),
                "tap" => await _bridge.PostJsonAsync("/tap", args),
                "swipe" => await _bridge.PostJsonAsync("/swipe", args),
                "global" => await _bridge.PostJsonAsync("/global", args),
                "shell" => await _bridge.PostJsonAsync("/shell", args),
                "finish" => FinishResult(args),
                _ => $"{{\"ok\":false,\"error\":\"unknown tool {call.Function.Name}\"}}",
            };
        }
        catch (Exception ex)
        {
            return $"{{\"ok\":false,\"error\":{JsonValue.Create(ex.Message)!.ToJsonString()}}}";
        }
    }

    private static JsonObject ParseArgs(string? raw)
    {
        if (string.IsNullOrWhiteSpace(raw)) return new JsonObject();
        try
        {
            return JsonNode.Parse(raw) as JsonObject ?? new JsonObject();
        }
        catch
        {
            return new JsonObject();
        }
    }

    private static string FinishResult(JsonObject args)
    {
        var summary = args["summary"]?.ToString() ?? "";
        return new JsonObject
        {
            ["ok"] = true,
            ["summary"] = summary,
        }.ToJsonString();
    }

    private static string Truncate(string s, int max) =>
        s.Length <= max ? s : s[..max] + "…";

    private static JsonArray BuildTools()
    {
        JsonObject Fn(string name, string desc, JsonObject props, string[] required) => new()
        {
            ["type"] = "function",
            ["function"] = new JsonObject
            {
                ["name"] = name,
                ["description"] = desc,
                ["parameters"] = new JsonObject
                {
                    ["type"] = "object",
                    ["properties"] = props,
                    ["required"] = new JsonArray(required.Select(r => JsonValue.Create(r)!).ToArray()),
                },
            },
        };

        JsonObject Num(string desc) => new() { ["type"] = "number", ["description"] = desc };
        JsonObject Str(string desc) => new() { ["type"] = "string", ["description"] = desc };

        return new JsonArray
        {
            Fn("get_screen", "读取当前屏幕可见节点（文本、坐标、可点击性）。", new JsonObject(), Array.Empty<string>()),
            Fn("tap", "点击屏幕。可用坐标或文本二者之一。", new JsonObject
            {
                ["x"] = Num("X 物理像素"),
                ["y"] = Num("Y 物理像素"),
                ["text"] = Str("要点击的文本"),
            }, Array.Empty<string>()),
            Fn("swipe", "从 (x1,y1) 滑动到 (x2,y2)。", new JsonObject
            {
                ["x1"] = Num("起点 X"),
                ["y1"] = Num("起点 Y"),
                ["x2"] = Num("终点 X"),
                ["y2"] = Num("终点 Y"),
                ["duration"] = Num("持续毫秒，默认 300"),
            }, new[] { "x1", "y1", "x2", "y2" }),
            Fn("global", "系统导航动作。", new JsonObject
            {
                ["action"] = new JsonObject
                {
                    ["type"] = "string",
                    ["enum"] = new JsonArray("back", "home", "recents", "notifications"),
                    ["description"] = "导航动作",
                },
            }, new[] { "action" }),
            Fn("shell", "以 Shizuku/Root 身份执行 shell 命令（需高权限模式）。", new JsonObject
            {
                ["cmd"] = Str("要执行的命令"),
            }, new[] { "cmd" }),
            Fn("finish", "任务结束并给出总结。", new JsonObject
            {
                ["summary"] = Str("结果总结"),
            }, new[] { "summary" }),
        };
    }
}

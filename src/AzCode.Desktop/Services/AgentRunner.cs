using System.Text.Json.Nodes;
using AzCode.Desktop.Models;

namespace AzCode.Desktop.Services;

/// <summary>
/// Agent 决策循环：读取本机桌面控件树 → 交给 DeepSeek 决策 → 执行鼠标/键盘/命令 → 回灌结果。
/// </summary>
public sealed class AgentRunner
{
    private readonly AppConfig _cfg;
    private readonly DeepSeekClient _llm = new();

    public event Action<string>? Log;

    public AgentRunner(AppConfig cfg)
    {
        _cfg = cfg;
    }

    private const string SystemPrompt = """
        你是 AzCode，一个运行在 Windows 电脑本地的自动化助手，直接控制这台电脑。
        每一步先调用 get_screen 观察当前活动窗口的控件树（名称/类型/坐标）与窗口标题，再选择动作。
        点击优先用 click 的 text 字段匹配控件名称，匹配不到时再用坐标。
        输入文字用 type；组合键用 key（如 "ctrl+s"、"enter"、"alt+f4"）。
        需要执行系统操作（启动程序、文件操作、查询信息）时用 shell（PowerShell）。
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
                var result = Execute(call);
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

    private static string Execute(ToolCall call)
    {
        var args = ParseArgs(call.Function.Arguments);
        try
        {
            switch (call.Function.Name)
            {
                case "get_screen":
                    return WindowsAutomation.DumpScreenJson();

                case "click":
                {
                    var text = args["text"]?.ToString();
                    if (!string.IsNullOrWhiteSpace(text))
                        return WindowsAutomation.ClickByText(text) ? Ok() : Err($"未找到控件：{text}");
                    if (args["x"] is not null && args["y"] is not null)
                    {
                        WindowsAutomation.ClickAt((int)args["x"]!.GetValue<double>(), (int)args["y"]!.GetValue<double>());
                        return Ok();
                    }
                    return Err("需要 text 或 x/y");
                }

                case "type":
                {
                    var text = args["text"]?.ToString();
                    if (string.IsNullOrEmpty(text)) return Err("缺少 text");
                    WindowsAutomation.TypeText(text);
                    return Ok();
                }

                case "key":
                {
                    var keys = args["keys"]?.ToString();
                    if (string.IsNullOrWhiteSpace(keys)) return Err("缺少 keys");
                    WindowsAutomation.PressKeys(keys);
                    return Ok();
                }

                case "shell":
                {
                    var cmd = args["cmd"]?.ToString();
                    if (string.IsNullOrWhiteSpace(cmd)) return Err("缺少 cmd");
                    return WindowsAutomation.Shell(cmd);
                }

                case "finish":
                    return new JsonObject { ["ok"] = true, ["summary"] = args["summary"]?.ToString() ?? "" }.ToJsonString();

                default:
                    return Err($"未知工具 {call.Function.Name}");
            }
        }
        catch (Exception ex)
        {
            return Err(ex.Message);
        }
    }

    private static string Ok() => """{"ok":true}""";

    private static string Err(string message) =>
        new JsonObject { ["ok"] = false, ["error"] = message }.ToJsonString();

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
            Fn("get_screen", "读取当前活动窗口的控件树（名称、类型、坐标）与窗口标题。", new JsonObject(), Array.Empty<string>()),
            Fn("click", "点击控件或坐标。优先按控件名称。", new JsonObject
            {
                ["text"] = Str("要点击的控件名称（包含匹配）"),
                ["x"] = Num("X 屏幕坐标"),
                ["y"] = Num("Y 屏幕坐标"),
            }, Array.Empty<string>()),
            Fn("type", "向当前焦点输入文本。", new JsonObject
            {
                ["text"] = Str("要输入的文本"),
            }, new[] { "text" }),
            Fn("key", "按下组合键，如 ctrl+s、enter、alt+f4、win。", new JsonObject
            {
                ["keys"] = Str("组合键"),
            }, new[] { "keys" }),
            Fn("shell", "执行 PowerShell 命令。", new JsonObject
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

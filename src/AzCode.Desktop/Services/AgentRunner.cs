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

    public async Task RunAsync(string task, CancellationToken ct)
    {
        // 前缀稳定：第 0 条 system 为稳定人设，技能/插件等运行时上下文仅在变化时
        // 追加到历史末尾；历史 append-only，从而最大化提供商前缀缓存命中率。
        var history = ConversationStore.Load();
        var messages = PromptCache.Assemble(_cfg, history, task);
        var tools = BuildTools();
        var _skills = new SkillStore();

        try
        {
            for (var step = 1; _cfg.MaxSteps <= 0 || step <= _cfg.MaxSteps; step++)
            {
                ct.ThrowIfCancellationRequested();
                var limit = _cfg.MaxSteps <= 0 ? "∞" : _cfg.MaxSteps.ToString();
                Log?.Invoke($"[step {step}/{limit}] 请求模型…");

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
                    var result = await ExecuteAsync(call, _skills, ct);
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
        finally
        {
            // 历史 append-only：仅去掉第 0 条稳定前缀，保留运行时 system 与全部对话。
            ConversationStore.Save(messages.Skip(1).ToList());
        }
    }

    private static async Task<string> ExecuteAsync(ToolCall call, SkillStore skills, CancellationToken ct)
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

                case "search_plugins":
                    return await SearchPluginsAsync(
                        skills,
                        args["keyword"]?.ToString() ?? "",
                        args["limit"] is null ? 8 : (int)args["limit"]!.GetValue<double>(),
                        ct);

                case "install_plugin":
                {
                    var url = args["url"]?.ToString() ?? "";
                    if (string.IsNullOrWhiteSpace(url)) return Err("缺少 url");
                    var installed = await skills.InstallAsync(url, ct);
                    return new JsonObject { ["ok"] = true, ["installed"] = installed.Name }.ToJsonString();
                }

                case "list_installed_plugins":
                    return ListPlugins(skills);

                case "set_plugin_enabled":
                {
                    var target = ResolveSkill(skills, args["id"]?.ToString() ?? "", args["name"]?.ToString() ?? "");
                    if (target is null) return Err("未找到该插件");
                    var enabled = args["enabled"] is null || args["enabled"]!.GetValue<bool>();
                    skills.SetEnabled(target.Id, enabled);
                    return new JsonObject { ["ok"] = true, ["name"] = target.Name, ["enabled"] = enabled }.ToJsonString();
                }

                case "remove_plugin":
                {
                    var target = ResolveSkill(skills, args["id"]?.ToString() ?? "", args["name"]?.ToString() ?? "");
                    if (target is null) return Err("未找到该插件");
                    skills.Remove(target.Id);
                    return new JsonObject { ["ok"] = true, ["removed"] = target.Name }.ToJsonString();
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

    private static async Task<string> SearchPluginsAsync(
        SkillStore skills, string keyword, int limit, CancellationToken ct)
    {
        var plugins = await skills.SearchAsync(keyword, limit, ct);
        var arr = new JsonArray();
        foreach (var p in plugins)
            arr.Add(new JsonObject
            {
                ["id"] = p.Id,
                ["name"] = p.Name,
                ["description"] = p.Description,
                ["installUrl"] = p.InstallUrl,
                ["stars"] = p.Stars,
                ["tags"] = new JsonArray(p.Tags.Select(t => JsonValue.Create(t)!).ToArray()),
            });
        return new JsonObject { ["ok"] = true, ["count"] = plugins.Count, ["plugins"] = arr }.ToJsonString();
    }

    private static string ListPlugins(SkillStore skills)
    {
        var arr = new JsonArray();
        var all = skills.All();
        foreach (var s in all)
            arr.Add(new JsonObject
            {
                ["id"] = s.Id,
                ["name"] = s.Name,
                ["description"] = s.Description,
                ["enabled"] = s.Enabled,
                ["source"] = string.IsNullOrWhiteSpace(s.Source) ? "内置" : s.Source,
            });
        return new JsonObject { ["ok"] = true, ["count"] = all.Count, ["plugins"] = arr }.ToJsonString();
    }

    private static Skill? ResolveSkill(SkillStore skills, string id, string name)
    {
        var all = skills.All();
        return all.FirstOrDefault(s => id.Length > 0 && s.Id == id)
               ?? all.FirstOrDefault(s => name.Length > 0 && s.Name == name)
               ?? all.FirstOrDefault(s => name.Length > 0 && s.Name.Contains(name));
    }

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
            Fn("search_plugins", "扫描热门开源仓库，查找可安装的 Agent 插件（技能）。", new JsonObject
            {
                ["keyword"] = Str("搜索关键词，留空返回内置精选"),
                ["limit"] = Num("最多返回条数，默认 8"),
            }, Array.Empty<string>()),
            Fn("install_plugin", "一键安装插件：下载仓库中的 SKILL.md 并写入本机技能库。", new JsonObject
            {
                ["url"] = Str("候选的 installUrl"),
            }, new[] { "url" }),
            Fn("list_installed_plugins", "列出本机已安装的插件及其启停状态。", new JsonObject(), Array.Empty<string>()),
            Fn("set_plugin_enabled", "启用或停用一个已安装插件。", new JsonObject
            {
                ["id"] = Str("插件 id"),
                ["name"] = Str("插件名称"),
                ["enabled"] = new JsonObject { ["type"] = "boolean", ["description"] = "是否启用" },
            }, Array.Empty<string>()),
            Fn("remove_plugin", "删除一个已安装插件。", new JsonObject
            {
                ["id"] = Str("插件 id"),
                ["name"] = Str("插件名称"),
            }, Array.Empty<string>()),
            Fn("finish", "任务结束并给出总结。", new JsonObject
            {
                ["summary"] = Str("结果总结"),
            }, new[] { "summary" }),
        };
    }
}

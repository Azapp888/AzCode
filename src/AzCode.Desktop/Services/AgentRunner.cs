using System.Text;
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

    /// <summary>结构化界面事件：驱动气泡、工具卡与「正在输入」状态。</summary>
    public event Action<AgentUiEvent>? UiEvent;

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
                UiEvent?.Invoke(new AgentUiEvent(AgentUiKind.Typing, ""));

                var msg = await _llm.ChatAsync(_cfg, messages, tools, ct);
                messages.Add(msg);

                if (!string.IsNullOrWhiteSpace(msg.Content))
                {
                    UiEvent?.Invoke(new AgentUiEvent(AgentUiKind.Assistant, msg.Content!));
                }

                if (msg.ToolCalls is null || msg.ToolCalls.Count == 0)
                {
                    Log?.Invoke($"完成：{msg.Content}");
                    return;
                }

                foreach (var call in msg.ToolCalls)
                {
                    ct.ThrowIfCancellationRequested();
                    UiEvent?.Invoke(new AgentUiEvent(AgentUiKind.ToolStart, call.Function.Arguments, call.Function.Name));
                    var result = await ExecuteAsync(call, _skills, ct);
                    Log?.Invoke($"  {call.Function.Name} -> {Truncate(result, 500)}");
                    UiEvent?.Invoke(new AgentUiEvent(AgentUiKind.ToolEnd, result, call.Function.Name));
                    messages.Add(new ChatMessage
                    {
                        Role = "tool",
                        ToolCallId = call.Id,
                        Content = result,
                    });
                }
            }

            Log?.Invoke($"达到最大步数 {_cfg.MaxSteps}，停止。");
            UiEvent?.Invoke(new AgentUiEvent(AgentUiKind.System, $"达到最大步数 {_cfg.MaxSteps}，已停止。"));
        }
        finally
        {
            // 历史 append-only：仅去掉第 0 条稳定前缀，保留运行时 system 与全部对话。
            ConversationStore.Save(messages.Skip(1).ToList());
        }
    }

    private void Ui(AgentUiEvent e) => UiEvent?.Invoke(e);

    private async Task<string> ExecuteAsync(ToolCall call, SkillStore skills, CancellationToken ct)
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
                        _cfg.GitHubToken,
                        ct);

                case "install_plugin":
                {
                    var url = args["url"]?.ToString() ?? "";
                    if (string.IsNullOrWhiteSpace(url)) return Err("缺少 url");
                    var installed = await skills.InstallAsync(url, ct, _cfg.GitHubToken);
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

                // ---- GitHub 仓库接入 ----
                case "github_status":
                    return await GitHubStatusAsync(ct);

                case "github_save_config":
                {
                    var token = args["token"]?.ToString() ?? "";
                    if (string.IsNullOrWhiteSpace(token)) return Err("缺少 token");
                    var user = await GitHubClient.WhoAmIAsync(token, ct);
                    _cfg.GitHubToken = token.Trim();
                    if (args["defaultRepo"] is not null) _cfg.GitHubDefaultRepo = args["defaultRepo"]!.ToString().Trim();
                    if (args["defaultBranch"] is not null) _cfg.GitHubDefaultBranch = args["defaultBranch"]!.ToString().Trim();
                    _cfg.GitHubLogin = user["login"]?.ToString() ?? "";
                    _cfg.Save();
                    return new JsonObject
                    {
                        ["ok"] = true,
                        ["login"] = _cfg.GitHubLogin,
                        ["defaultRepo"] = _cfg.GitHubDefaultRepo,
                        ["defaultBranch"] = _cfg.GitHubDefaultBranch,
                    }.ToJsonString();
                }

                case "github_list_repos":
                    return await GitHubListReposAsync(args, ct);

                case "github_get_repo":
                    return await GitHubGetRepoAsync(args, ct);

                case "github_list_branches":
                    return await GitHubListBranchesAsync(args, ct);

                case "github_read_file":
                    return await GitHubReadFileAsync(args, ct);

                case "github_write_file":
                    return await GitHubWriteFileAsync(args, ct);

                case "github_list_commits":
                    return await GitHubListCommitsAsync(args, ct);

                case "github_list_issues":
                    return await GitHubListIssuesAsync(args, ct);

                case "github_create_issue":
                    return await GitHubCreateIssueAsync(args, ct);

                case "github_comment_issue":
                    return await GitHubCommentIssueAsync(args, ct);

                case "github_list_pulls":
                    return await GitHubListPullsAsync(args, ct);

                case "github_create_pull":
                    return await GitHubCreatePullAsync(args, ct);

                case "github_search_repos":
                    return await GitHubSearchReposAsync(args, ct);

                default:
                    return Err($"未知工具 {call.Function.Name}");
            }
        }
        catch (Exception ex)
        {
            return Err(ex.Message);
        }
    }

    // ==================== GitHub 仓库 ====================

    private string RepoOr(JsonObject args)
    {
        var repo = args["repo"]?.ToString()?.Trim() ?? "";
        return repo.Length > 0 ? repo : _cfg.GitHubDefaultRepo;
    }

    private string BranchOr(JsonObject args)
    {
        var branch = (args["branch"]?.ToString() ?? args["ref"]?.ToString() ?? "").Trim();
        return branch.Length > 0 ? branch : _cfg.GitHubDefaultBranch;
    }

    private static int IntOr(JsonObject args, string key, int fallback)
    {
        if (args[key] is null) return fallback;
        try { return (int)args[key]!.GetValue<double>(); } catch { return fallback; }
    }

    private async Task<string> GitHubStatusAsync(CancellationToken ct)
    {
        var outObj = new JsonObject
        {
            ["ok"] = true,
            ["configured"] = _cfg.GitHubConfigured,
            ["defaultRepo"] = _cfg.GitHubDefaultRepo,
            ["defaultBranch"] = _cfg.GitHubDefaultBranch,
        };
        if (!_cfg.GitHubConfigured)
        {
            outObj["hint"] = "未配置 Token。可让用户提供 Token 后调用 github_save_config。";
            return outObj.ToJsonString();
        }
        try
        {
            var user = await GitHubClient.WhoAmIAsync(_cfg.GitHubToken, ct);
            _cfg.GitHubLogin = user["login"]?.ToString() ?? "";
            outObj["login"] = _cfg.GitHubLogin;
            outObj["name"] = user["name"]?.ToString() ?? "";
            outObj["publicRepos"] = user["public_repos"]?.GetValue<int>() ?? 0;
            var priv = user["total_private_repos"]?.GetValue<int>() ?? 0;
            outObj["totalRepos"] = priv + (user["public_repos"]?.GetValue<int>() ?? 0);
            outObj["tokenValid"] = true;
            return outObj.ToJsonString();
        }
        catch (Exception ex)
        {
            outObj["tokenValid"] = false;
            outObj["error"] = ex.Message;
            return outObj.ToJsonString();
        }
    }

    private async Task<string> GitHubListReposAsync(JsonObject args, CancellationToken ct)
    {
        if (!_cfg.GitHubConfigured) return Err(NoTokenHint);
        var arr = await GitHubClient.ListReposAsync(_cfg.GitHubToken, IntOr(args, "limit", 30), ct);
        var outArr = new JsonArray();
        foreach (var node in arr)
        {
            if (node is not JsonObject r) continue;
            outArr.Add(new JsonObject
            {
                ["fullName"] = r["full_name"]?.ToString() ?? "",
                ["private"] = r["private"]?.GetValue<bool>() ?? false,
                ["defaultBranch"] = r["default_branch"]?.ToString() ?? "",
                ["description"] = r["description"]?.ToString() ?? "",
                ["stars"] = r["stargazers_count"]?.GetValue<int>() ?? 0,
                ["updatedAt"] = r["updated_at"]?.ToString() ?? "",
            });
        }
        return new JsonObject { ["ok"] = true, ["count"] = outArr.Count, ["repos"] = outArr }.ToJsonString();
    }

    private async Task<string> GitHubGetRepoAsync(JsonObject args, CancellationToken ct)
    {
        if (!_cfg.GitHubConfigured) return Err(NoTokenHint);
        var repo = RepoOr(args);
        if (repo.Length == 0) return Err("请指定仓库，或先设置默认仓库");
        var r = await GitHubClient.GetRepoAsync(_cfg.GitHubToken, repo, ct);
        return new JsonObject
        {
            ["ok"] = true,
            ["fullName"] = r["full_name"]?.ToString() ?? "",
            ["private"] = r["private"]?.GetValue<bool>() ?? false,
            ["defaultBranch"] = r["default_branch"]?.ToString() ?? "",
            ["description"] = r["description"]?.ToString() ?? "",
            ["stars"] = r["stargazers_count"]?.GetValue<int>() ?? 0,
            ["openIssues"] = r["open_issues_count"]?.GetValue<int>() ?? 0,
            ["htmlUrl"] = r["html_url"]?.ToString() ?? "",
        }.ToJsonString();
    }

    private async Task<string> GitHubListBranchesAsync(JsonObject args, CancellationToken ct)
    {
        if (!_cfg.GitHubConfigured) return Err(NoTokenHint);
        var repo = RepoOr(args);
        if (repo.Length == 0) return Err("请指定仓库，或先设置默认仓库");
        var arr = await GitHubClient.ListBranchesAsync(_cfg.GitHubToken, repo, ct);
        var outArr = new JsonArray();
        foreach (var node in arr)
            if (node is JsonObject b) outArr.Add(b["name"]?.ToString() ?? "");
        return new JsonObject { ["ok"] = true, ["branches"] = outArr }.ToJsonString();
    }

    private async Task<string> GitHubReadFileAsync(JsonObject args, CancellationToken ct)
    {
        if (!_cfg.GitHubConfigured) return Err(NoTokenHint);
        var repo = RepoOr(args);
        var path = args["path"]?.ToString()?.Trim() ?? "";
        if (repo.Length == 0) return Err("请指定仓库，或先设置默认仓库");
        if (path.Length == 0) return Err("缺少 path");
        var file = await GitHubClient.GetFileAsync(_cfg.GitHubToken, repo, path, BranchOr(args), ct);
        var encoded = file["content"]?.ToString()?.Replace("\n", "") ?? "";
        string decoded;
        try { decoded = Encoding.UTF8.GetString(Convert.FromBase64String(encoded)); }
        catch { decoded = ""; }
        return new JsonObject
        {
            ["ok"] = true,
            ["path"] = file["path"]?.ToString() ?? path,
            ["sha"] = file["sha"]?.ToString() ?? "",
            ["size"] = file["size"]?.GetValue<int>() ?? 0,
            ["content"] = decoded,
        }.ToJsonString();
    }

    private async Task<string> GitHubWriteFileAsync(JsonObject args, CancellationToken ct)
    {
        if (!_cfg.GitHubConfigured) return Err(NoTokenHint);
        var repo = RepoOr(args);
        var path = args["path"]?.ToString()?.Trim() ?? "";
        if (repo.Length == 0) return Err("请指定仓库，或先设置默认仓库");
        if (path.Length == 0) return Err("缺少 path");
        var content = args["content"]?.ToString() ?? "";
        var branch = BranchOr(args);
        var sha = args["sha"]?.ToString()?.Trim() ?? "";
        if (sha.Length == 0)
        {
            try { sha = (await GitHubClient.GetFileAsync(_cfg.GitHubToken, repo, path, branch, ct))["sha"]?.ToString() ?? ""; }
            catch { sha = ""; }
        }
        var res = await GitHubClient.PutFileAsync(_cfg.GitHubToken, repo, path, content, args["message"]?.ToString() ?? "", branch, sha, ct);
        var commit = res["commit"] as JsonObject;
        return new JsonObject
        {
            ["ok"] = true,
            ["path"] = path,
            ["updated"] = sha.Length > 0,
            ["commit"] = commit?["sha"]?.ToString() ?? "",
            ["htmlUrl"] = commit?["html_url"]?.ToString() ?? "",
        }.ToJsonString();
    }

    private async Task<string> GitHubListCommitsAsync(JsonObject args, CancellationToken ct)
    {
        if (!_cfg.GitHubConfigured) return Err(NoTokenHint);
        var repo = RepoOr(args);
        if (repo.Length == 0) return Err("请指定仓库，或先设置默认仓库");
        var arr = await GitHubClient.ListCommitsAsync(_cfg.GitHubToken, repo, BranchOr(args), IntOr(args, "limit", 20), ct);
        var outArr = new JsonArray();
        foreach (var node in arr)
        {
            if (node is not JsonObject c) continue;
            var commit = c["commit"] as JsonObject;
            var author = commit?["author"] as JsonObject;
            var msg = commit?["message"]?.ToString() ?? "";
            outArr.Add(new JsonObject
            {
                ["sha"] = (c["sha"]?.ToString() ?? "").PadRight(8)[..8],
                ["message"] = msg.Split('\n').FirstOrDefault() ?? "",
                ["author"] = author?["name"]?.ToString() ?? "",
                ["date"] = author?["date"]?.ToString() ?? "",
            });
        }
        return new JsonObject { ["ok"] = true, ["commits"] = outArr }.ToJsonString();
    }

    private async Task<string> GitHubListIssuesAsync(JsonObject args, CancellationToken ct)
    {
        if (!_cfg.GitHubConfigured) return Err(NoTokenHint);
        var repo = RepoOr(args);
        if (repo.Length == 0) return Err("请指定仓库，或先设置默认仓库");
        var arr = await GitHubClient.ListIssuesAsync(_cfg.GitHubToken, repo, args["state"]?.ToString() ?? "open", IntOr(args, "limit", 20), ct);
        var outArr = new JsonArray();
        foreach (var node in arr)
        {
            if (node is not JsonObject it) continue;
            outArr.Add(new JsonObject
            {
                ["number"] = it["number"]?.GetValue<int>() ?? 0,
                ["title"] = it["title"]?.ToString() ?? "",
                ["state"] = it["state"]?.ToString() ?? "",
                ["isPull"] = it["pull_request"] is not null,
                ["user"] = (it["user"] as JsonObject)?["login"]?.ToString() ?? "",
            });
        }
        return new JsonObject { ["ok"] = true, ["issues"] = outArr }.ToJsonString();
    }

    private async Task<string> GitHubCreateIssueAsync(JsonObject args, CancellationToken ct)
    {
        if (!_cfg.GitHubConfigured) return Err(NoTokenHint);
        var repo = RepoOr(args);
        var title = args["title"]?.ToString()?.Trim() ?? "";
        if (repo.Length == 0) return Err("请指定仓库，或先设置默认仓库");
        if (title.Length == 0) return Err("缺少 title");
        var res = await GitHubClient.CreateIssueAsync(_cfg.GitHubToken, repo, title, args["body"]?.ToString() ?? "", ct);
        return new JsonObject
        {
            ["ok"] = true,
            ["number"] = res["number"]?.GetValue<int>() ?? 0,
            ["htmlUrl"] = res["html_url"]?.ToString() ?? "",
        }.ToJsonString();
    }

    private async Task<string> GitHubCommentIssueAsync(JsonObject args, CancellationToken ct)
    {
        if (!_cfg.GitHubConfigured) return Err(NoTokenHint);
        var repo = RepoOr(args);
        var number = IntOr(args, "number", 0);
        var body = args["body"]?.ToString() ?? "";
        if (repo.Length == 0) return Err("请指定仓库，或先设置默认仓库");
        if (number <= 0) return Err("缺少 number");
        if (body.Length == 0) return Err("缺少 body");
        var res = await GitHubClient.CommentIssueAsync(_cfg.GitHubToken, repo, number, body, ct);
        return new JsonObject { ["ok"] = true, ["htmlUrl"] = res["html_url"]?.ToString() ?? "" }.ToJsonString();
    }

    private async Task<string> GitHubListPullsAsync(JsonObject args, CancellationToken ct)
    {
        if (!_cfg.GitHubConfigured) return Err(NoTokenHint);
        var repo = RepoOr(args);
        if (repo.Length == 0) return Err("请指定仓库，或先设置默认仓库");
        var arr = await GitHubClient.ListPullsAsync(_cfg.GitHubToken, repo, args["state"]?.ToString() ?? "open", IntOr(args, "limit", 20), ct);
        var outArr = new JsonArray();
        foreach (var node in arr)
        {
            if (node is not JsonObject p) continue;
            outArr.Add(new JsonObject
            {
                ["number"] = p["number"]?.GetValue<int>() ?? 0,
                ["title"] = p["title"]?.ToString() ?? "",
                ["state"] = p["state"]?.ToString() ?? "",
                ["head"] = (p["head"] as JsonObject)?["ref"]?.ToString() ?? "",
                ["base"] = (p["base"] as JsonObject)?["ref"]?.ToString() ?? "",
            });
        }
        return new JsonObject { ["ok"] = true, ["pulls"] = outArr }.ToJsonString();
    }

    private async Task<string> GitHubCreatePullAsync(JsonObject args, CancellationToken ct)
    {
        if (!_cfg.GitHubConfigured) return Err(NoTokenHint);
        var repo = RepoOr(args);
        var title = args["title"]?.ToString()?.Trim() ?? "";
        var head = args["head"]?.ToString()?.Trim() ?? "";
        var baseBranch = args["base"]?.ToString()?.Trim() ?? "";
        if (repo.Length == 0) return Err("请指定仓库，或先设置默认仓库");
        if (title.Length == 0 || head.Length == 0 || baseBranch.Length == 0) return Err("缺少 title/head/base");
        var res = await GitHubClient.CreatePullAsync(_cfg.GitHubToken, repo, title, head, baseBranch, args["body"]?.ToString() ?? "", ct);
        return new JsonObject
        {
            ["ok"] = true,
            ["number"] = res["number"]?.GetValue<int>() ?? 0,
            ["htmlUrl"] = res["html_url"]?.ToString() ?? "",
        }.ToJsonString();
    }

    private async Task<string> GitHubSearchReposAsync(JsonObject args, CancellationToken ct)
    {
        var query = args["query"]?.ToString()?.Trim() ?? "";
        if (query.Length == 0) return Err("缺少 query");
        var arr = await GitHubClient.SearchReposAsync(_cfg.GitHubToken, query, IntOr(args, "limit", 10), ct);
        var outArr = new JsonArray();
        foreach (var node in arr)
        {
            if (node is not JsonObject r) continue;
            outArr.Add(new JsonObject
            {
                ["fullName"] = r["full_name"]?.ToString() ?? "",
                ["description"] = r["description"]?.ToString() ?? "",
                ["stars"] = r["stargazers_count"]?.GetValue<int>() ?? 0,
                ["htmlUrl"] = r["html_url"]?.ToString() ?? "",
            });
        }
        return new JsonObject { ["ok"] = true, ["repos"] = outArr }.ToJsonString();
    }

    private const string NoTokenHint = "尚未接入 GitHub，请先在界面填写 Personal Access Token，或把 Token 发给我代为保存。";

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
        SkillStore skills, string keyword, int limit, string token, CancellationToken ct)
    {
        var plugins = await skills.SearchAsync(keyword, limit, token, ct);
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

            // ---- GitHub 仓库接入 ----
            Fn("github_status", "查看 GitHub 接入状态：是否已配置 Token、当前登录账号、默认仓库与分支。操作仓库前先调用。", new JsonObject(), Array.Empty<string>()),
            Fn("github_save_config", "保存 GitHub 接入配置（Token 与可选默认仓库/分支），保存前会校验 Token。", new JsonObject
            {
                ["token"] = Str("GitHub Personal Access Token（需 repo 权限）"),
                ["defaultRepo"] = Str("默认仓库 owner/repo，可省略"),
                ["defaultBranch"] = Str("默认分支，留空用仓库默认分支"),
            }, new[] { "token" }),
            Fn("github_list_repos", "列出当前 Token 可访问的仓库（含私有）。", new JsonObject
            {
                ["limit"] = Num("最多返回条数，默认 30"),
            }, Array.Empty<string>()),
            Fn("github_get_repo", "查看仓库概览：默认分支、是否私有、star、开放 Issue 数等。", new JsonObject
            {
                ["repo"] = Str("仓库 owner/repo，省略用默认仓库"),
            }, Array.Empty<string>()),
            Fn("github_list_branches", "列出仓库分支。", new JsonObject
            {
                ["repo"] = Str("仓库 owner/repo，省略用默认仓库"),
            }, Array.Empty<string>()),
            Fn("github_read_file", "读取仓库文件内容与 sha（更新文件时需要）。", new JsonObject
            {
                ["repo"] = Str("仓库 owner/repo，省略用默认仓库"),
                ["path"] = Str("文件路径"),
                ["ref"] = Str("分支或 commit，省略用默认分支"),
            }, new[] { "path" }),
            Fn("github_write_file", "创建或更新仓库文件并产生一次提交。更新已有文件可先读取文件，或由工具自动探测 sha。", new JsonObject
            {
                ["repo"] = Str("仓库 owner/repo，省略用默认仓库"),
                ["path"] = Str("文件路径"),
                ["content"] = Str("文件完整内容"),
                ["message"] = Str("提交信息"),
                ["branch"] = Str("提交到的分支，省略用默认分支"),
                ["sha"] = Str("更新已有文件时的 sha；新建留空"),
            }, new[] { "path", "content" }),
            Fn("github_list_commits", "列出仓库提交记录。", new JsonObject
            {
                ["repo"] = Str("仓库 owner/repo，省略用默认仓库"),
                ["ref"] = Str("分支或 commit，省略用默认分支"),
                ["limit"] = Num("最多返回条数，默认 20"),
            }, Array.Empty<string>()),
            Fn("github_list_issues", "列出仓库 Issue。", new JsonObject
            {
                ["repo"] = Str("仓库 owner/repo，省略用默认仓库"),
                ["state"] = Str("open / closed / all，默认 open"),
                ["limit"] = Num("最多返回条数，默认 20"),
            }, Array.Empty<string>()),
            Fn("github_create_issue", "新建 Issue。", new JsonObject
            {
                ["repo"] = Str("仓库 owner/repo，省略用默认仓库"),
                ["title"] = Str("Issue 标题"),
                ["body"] = Str("Issue 正文"),
            }, new[] { "title" }),
            Fn("github_comment_issue", "给 Issue 或 PR 添加评论。", new JsonObject
            {
                ["repo"] = Str("仓库 owner/repo，省略用默认仓库"),
                ["number"] = Num("Issue/PR 编号"),
                ["body"] = Str("评论内容"),
            }, new[] { "number", "body" }),
            Fn("github_list_pulls", "列出仓库 Pull Request。", new JsonObject
            {
                ["repo"] = Str("仓库 owner/repo，省略用默认仓库"),
                ["state"] = Str("open / closed / all，默认 open"),
                ["limit"] = Num("最多返回条数，默认 20"),
            }, Array.Empty<string>()),
            Fn("github_create_pull", "基于已有分支创建 Pull Request。", new JsonObject
            {
                ["repo"] = Str("仓库 owner/repo，省略用默认仓库"),
                ["title"] = Str("PR 标题"),
                ["head"] = Str("来源分支"),
                ["base"] = Str("目标分支"),
                ["body"] = Str("PR 描述"),
            }, new[] { "title", "head", "base" }),
            Fn("github_search_repos", "在 GitHub 搜索公开仓库（按 star 排序）。", new JsonObject
            {
                ["query"] = Str("搜索关键词"),
                ["limit"] = Num("最多返回条数，默认 10"),
            }, new[] { "query" }),

            Fn("finish", "任务结束并给出总结。", new JsonObject
            {
                ["summary"] = Str("结果总结"),
            }, new[] { "summary" }),
        };
    }
}

using System.Net.Http;
using System.Text;
using System.Text.Json.Nodes;

namespace AzCode.Desktop.Services;

/// <summary>
/// GitHub REST API 客户端（Repos / Contents / Issues / Pulls / Search）。
/// 只做用户显式请求的仓库读写：读取/提交文件、管理 Issue 与 Pull Request，不含删除仓库等不可逆操作。
/// Token 由用户自行提供并保存在本机配置中。
/// </summary>
public static class GitHubClient
{
    private const string Api = "https://api.github.com";
    private const string Ua = "AzCode-Desktop";

    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(30) };

    public static async Task<JsonObject> WhoAmIAsync(string token, CancellationToken ct) =>
        Obj(await RequestAsync(token, HttpMethod.Get, "/user", null, ct));

    public static async Task<JsonArray> ListReposAsync(string token, int limit, CancellationToken ct)
    {
        var outArr = new JsonArray();
        var perPage = Math.Clamp(limit, 1, 100);
        for (var page = 1; page <= 5 && outArr.Count < perPage; page++)
        {
            var arr = ParseArray(await RequestAsync(
                token, HttpMethod.Get, $"/user/repos?per_page={perPage}&page={page}&sort=updated", null, ct));
            foreach (var node in arr) outArr.Add(node?.DeepClone());
            if (arr.Count < perPage) break;
        }
        return outArr;
    }

    public static async Task<JsonObject> GetRepoAsync(string token, string repo, CancellationToken ct) =>
        Obj(await RequestAsync(token, HttpMethod.Get, $"/repos/{repo}", null, ct));

    public static async Task<JsonArray> ListBranchesAsync(string token, string repo, CancellationToken ct) =>
        ParseArray(await RequestAsync(token, HttpMethod.Get, $"/repos/{repo}/branches?per_page=100", null, ct));

    public static async Task<JsonArray> ListCommitsAsync(string token, string repo, string @ref, int limit, CancellationToken ct)
    {
        var url = $"/repos/{repo}/commits?per_page={Math.Clamp(limit, 1, 100)}";
        if (@ref.Length > 0) url += $"&sha={Uri.EscapeDataString(@ref)}";
        return ParseArray(await RequestAsync(token, HttpMethod.Get, url, null, ct));
    }

    public static async Task<JsonObject> GetFileAsync(string token, string repo, string path, string @ref, CancellationToken ct)
    {
        var url = $"/repos/{repo}/contents/{EncPath(path)}";
        if (@ref.Length > 0) url += $"?ref={Uri.EscapeDataString(@ref)}";
        return Obj(await RequestAsync(token, HttpMethod.Get, url, null, ct));
    }

    /// <summary>创建或更新文件并产生一次提交；更新已有文件需提供其 sha，新建时 sha 传空。</summary>
    public static async Task<JsonObject> PutFileAsync(
        string token, string repo, string path, string content, string message, string branch, string sha, CancellationToken ct)
    {
        var body = new JsonObject
        {
            ["message"] = string.IsNullOrWhiteSpace(message) ? $"Update {path}" : message,
            ["content"] = Convert.ToBase64String(Encoding.UTF8.GetBytes(content)),
        };
        if (branch.Length > 0) body["branch"] = branch;
        if (sha.Length > 0) body["sha"] = sha;
        return Obj(await RequestAsync(token, HttpMethod.Put, $"/repos/{repo}/contents/{EncPath(path)}", body, ct));
    }

    public static async Task<JsonArray> ListIssuesAsync(string token, string repo, string state, int limit, CancellationToken ct) =>
        ParseArray(await RequestAsync(
            token, HttpMethod.Get,
            $"/repos/{repo}/issues?state={Uri.EscapeDataString(state)}&per_page={Math.Clamp(limit, 1, 100)}", null, ct));

    public static async Task<JsonObject> CreateIssueAsync(string token, string repo, string title, string body, CancellationToken ct)
    {
        var payload = new JsonObject { ["title"] = title };
        if (body.Length > 0) payload["body"] = body;
        return Obj(await RequestAsync(token, HttpMethod.Post, $"/repos/{repo}/issues", payload, ct));
    }

    public static async Task<JsonObject> CommentIssueAsync(string token, string repo, int number, string body, CancellationToken ct) =>
        Obj(await RequestAsync(
            token, HttpMethod.Post, $"/repos/{repo}/issues/{number}/comments",
            new JsonObject { ["body"] = body }, ct));

    public static async Task<JsonArray> ListPullsAsync(string token, string repo, string state, int limit, CancellationToken ct) =>
        ParseArray(await RequestAsync(
            token, HttpMethod.Get,
            $"/repos/{repo}/pulls?state={Uri.EscapeDataString(state)}&per_page={Math.Clamp(limit, 1, 100)}", null, ct));

    public static async Task<JsonObject> CreatePullAsync(
        string token, string repo, string title, string head, string baseBranch, string body, CancellationToken ct)
    {
        var payload = new JsonObject { ["title"] = title, ["head"] = head, ["base"] = baseBranch };
        if (body.Length > 0) payload["body"] = body;
        return Obj(await RequestAsync(token, HttpMethod.Post, $"/repos/{repo}/pulls", payload, ct));
    }

    public static async Task<JsonArray> SearchReposAsync(string token, string query, int limit, CancellationToken ct)
    {
        var q = Uri.EscapeDataString(query);
        var res = Obj(await RequestAsync(
            token, HttpMethod.Get,
            $"/search/repositories?q={q}&sort=stars&order=desc&per_page={Math.Clamp(limit, 1, 30)}", null, ct));
        return res["items"] as JsonArray ?? new JsonArray();
    }

    // ==================== 基础请求 ====================

    private static async Task<string> RequestAsync(
        string token, HttpMethod method, string path, JsonObject? body, CancellationToken ct)
    {
        using var req = new HttpRequestMessage(method, Api + path);
        req.Headers.TryAddWithoutValidation("Accept", "application/vnd.github+json");
        req.Headers.TryAddWithoutValidation("User-Agent", Ua);
        req.Headers.TryAddWithoutValidation("X-GitHub-Api-Version", "2022-11-28");
        if (token.Length > 0) req.Headers.TryAddWithoutValidation("Authorization", $"Bearer {token}");
        if (body is not null)
            req.Content = new StringContent(body.ToJsonString(), Encoding.UTF8, "application/json");

        using var resp = await Http.SendAsync(req, ct);
        var text = await resp.Content.ReadAsStringAsync(ct);
        if (!resp.IsSuccessStatusCode)
            throw new InvalidOperationException(DescribeError((int)resp.StatusCode, text));
        return text;
    }

    private static string DescribeError(int code, string body)
    {
        var message = "";
        try { message = JsonNode.Parse(body)?["message"]?.ToString() ?? ""; } catch { /* ignore */ }
        return code switch
        {
            401 => "GitHub 未授权（401）：Token 无效或已过期，请重新填写。",
            403 => $"GitHub 拒绝访问（403）：{(message.Length > 0 ? message : "Token 权限不足或触发频率限制，请确认已勾选 repo 权限。")}",
            404 => $"未找到（404）：仓库、分支或文件不存在，或 Token 无该私有仓库权限。{message}",
            409 => $"冲突（409）：文件已被他人修改，请重新读取后再提交。{message}",
            422 => $"请求无效（422）：{message}",
            _ => $"GitHub 请求失败（{code}）：{message}",
        };
    }

    private static JsonObject Obj(string text) => JsonNode.Parse(text) as JsonObject ?? new JsonObject();

    private static JsonArray ParseArray(string text) => JsonNode.Parse(text) as JsonArray ?? new JsonArray();

    /// <summary>逐段编码路径，保留斜杠。</summary>
    private static string EncPath(string path) =>
        string.Join('/', path.Trim('/').Split('/', StringSplitOptions.RemoveEmptyEntries)
            .Select(Uri.EscapeDataString));
}

using System.IO;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using AzCode.Desktop.Models;

namespace AzCode.Desktop.Services;

/// <summary>一个「技能」：一段注入运行时上下文的指令文本，可来自内置或 GitHub。</summary>
public sealed class Skill
{
    public string Id { get; set; } = "";
    public string Name { get; set; } = "";
    public string Description { get; set; } = "";
    public string Content { get; set; } = "";
    public string Source { get; set; } = "";
    public bool Enabled { get; set; } = true;
}

/// <summary>
/// 技能持久化（config 目录下 skills.json）与热门插件扫描/安装。
/// 内置 ponytail（拒绝过度设计）随应用提供。
/// </summary>
public sealed class SkillStore
{
    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(25) };
    private static readonly JsonSerializerOptions Options = new() { WriteIndented = true };

    public const string PonytailId = "builtin-ponytail";

    public const string PonytailContent = """
        # ponytail · 拒绝过度设计

        你是一个克制的工程师。每次动手前，先确认「解决当前问题所需的最小改动」，然后只做这些。

        原则：
        1. 只实现用户要求的功能，不擅自增加配置项、开关、抽象层或「以后可能用到」的能力。
        2. 优先使用已有代码与标准库；新增依赖必须说明为什么现有手段无法完成。
        3. 修改范围尽可能小，一次只解决一个问题，不顺手重构无关代码。
        4. 能在一个文件解决就不用两个；能不引入接口/工厂/基类就直接写具体实现。
        5. 遇到不确定的需求，先向用户确认，不要凭猜测扩大实现。
        6. 完成后用一两句话说明改动，并注明是否引入了新的依赖或文件。

        判断口诀：如果这段代码今天没有明确用途，就不要写。
        """;

    public static string SkillsPath => Path.Combine(AppConfig.ConfigDir, "skills.json");

    public List<Skill> All()
    {
        try
        {
            if (File.Exists(SkillsPath))
                return JsonSerializer.Deserialize<List<Skill>>(File.ReadAllText(SkillsPath), Options) ?? new();
        }
        catch
        {
            // 文件损坏时回退为空
        }
        return new List<Skill>();
    }

    public List<Skill> Enabled() => All().Where(s => s.Enabled).ToList();

    private void Write(List<Skill> skills)
    {
        Directory.CreateDirectory(AppConfig.ConfigDir);
        File.WriteAllText(SkillsPath, JsonSerializer.Serialize(skills, Options));
    }

    public void Save(Skill skill)
    {
        var list = All();
        var index = list.FindIndex(s => s.Id == skill.Id);
        if (index >= 0) list[index] = skill; else list.Add(skill);
        Write(list);
    }

    public void Remove(string id) => Write(All().Where(s => s.Id != id).ToList());

    public void SetEnabled(string id, bool enabled)
    {
        var list = All();
        foreach (var s in list)
            if (s.Id == id) s.Enabled = enabled;
        Write(list);
    }

    /// <summary>首次运行时写入内置技能（幂等，不覆盖用户对启停的选择）。</summary>
    public void SeedBuiltins()
    {
        if (All().Any(s => s.Id == PonytailId)) return;
        Save(new Skill
        {
            Id = PonytailId,
            Name = "ponytail · 拒绝过度设计",
            Description = "只做最小必要改动，避免多余依赖、抽象与投机功能。",
            Content = PonytailContent.Trim(),
            Source = "",
            Enabled = true,
        });
    }

    // ------------------------------------------------------------ 插件市场

    public sealed class Plugin
    {
        public string Id { get; set; } = "";
        public string Name { get; set; } = "";
        public string Description { get; set; } = "";
        public string InstallUrl { get; set; } = "";
        public int Stars { get; set; }
        public List<string> Tags { get; set; } = new();
    }

    private static readonly List<Plugin> Curated = new()
    {
        new Plugin
        {
            Id = "ponytail",
            Name = "ponytail · 拒绝过度设计",
            Description = "让 Agent 只做最小必要改动：不引入多余依赖、不预先抽象、不写投机功能。",
            InstallUrl = "ilindaniel/ponytail-lite",
            Tags = new() { "工程", "简洁", "反过度设计" },
        },
    };

    /// <summary>内置精选 + GitHub star 排序结果。</summary>
    public async Task<List<Plugin>> SearchAsync(string keyword, int limit = 8, CancellationToken ct = default)
    {
        var kw = (keyword ?? "").Trim();
        var result = new List<Plugin>();
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        void Add(Plugin p)
        {
            if (seen.Add(p.InstallUrl)) result.Add(p);
        }

        if (kw.Length == 0)
        {
            Curated.ForEach(Add);
            return result;
        }

        var lc = kw.ToLowerInvariant();
        foreach (var p in Curated.Where(p =>
                     p.Name.ToLowerInvariant().Contains(lc) ||
                     p.Description.ToLowerInvariant().Contains(lc) ||
                     p.Tags.Any(t => t.ToLowerInvariant().Contains(lc))))
            Add(p);

        try
        {
            var q = Uri.EscapeDataString($"{kw} SKILL.md in:name,description,readme");
            var url = $"https://api.github.com/search/repositories?q={q}&sort=stars&order=desc&per_page={Math.Clamp(limit, 1, 20)}";
            using var req = new HttpRequestMessage(HttpMethod.Get, url);
            req.Headers.Add("Accept", "application/vnd.github+json");
            req.Headers.Add("User-Agent", "AzCode-Windows");
            using var resp = await Http.SendAsync(req, ct);
            if (!resp.IsSuccessStatusCode) return result.Take(limit + Curated.Count).ToList();
            var node = JsonNode.Parse(await resp.Content.ReadAsStringAsync(ct));
            var items = node?["items"]?.AsArray();
            if (items is null) return result.Take(limit + Curated.Count).ToList();
            foreach (var item in items)
            {
                var full = item?["full_name"]?.ToString();
                if (string.IsNullOrWhiteSpace(full)) continue;
                Add(new Plugin
                {
                    Id = "gh-" + full.Replace('/', '-'),
                    Name = full,
                    Description = (item?["description"]?.ToString() ?? "").Truncate(160),
                    InstallUrl = full,
                    Stars = item?["stargazers_count"]?.GetValue<int>() ?? 0,
                });
            }
        }
        catch
        {
            // 网络不可用时只返回内置精选
        }

        return result.Take(limit + Curated.Count).ToList();
    }

    /// <summary>一键安装：下载并解析仓库中的 SKILL.md 写入技能库。</summary>
    public async Task<Skill> InstallAsync(string installUrl, CancellationToken ct = default)
    {
        var rawUrl = ResolveRawUrl(installUrl);
        var body = await DownloadAsync(rawUrl, ct);
        var (name, description, content) = Parse(body);
        var skill = new Skill
        {
            Id = "gh-" + Convert.ToHexString(
                System.Security.Cryptography.SHA1.HashData(Encoding.UTF8.GetBytes(rawUrl)))[..12].ToLowerInvariant(),
            Name = name,
            Description = description,
            Content = content,
            Source = rawUrl,
            Enabled = true,
        };
        Save(skill);
        return skill;
    }

    private static string ResolveRawUrl(string input)
    {
        input = (input ?? "").Trim();
        if (input.Length == 0) throw new InvalidOperationException("请输入 GitHub 链接");
        string url;
        if (input.StartsWith("http://") || input.StartsWith("https://")) url = input;
        else if (Regex.IsMatch(input, @"^[\w.-]+/[\w.-]+(/.*)?$")) url = "https://github.com/" + input;
        else throw new InvalidOperationException("无法识别的链接格式");

        const string rawBase = "https://raw.githubusercontent.com/";
        if (url.StartsWith(rawBase)) return url;

        var m = Regex.Match(url, @"^https?://github\.com/([^/]+)/([^/]+)(.*)$");
        if (!m.Success) throw new InvalidOperationException("只支持 github.com 或 raw.githubusercontent.com 链接");
        var owner = m.Groups[1].Value;
        var repo = m.Groups[2].Value.EndsWith(".git") ? m.Groups[2].Value[..^4] : m.Groups[2].Value;
        var rest = m.Groups[3].Value.TrimStart('/');

        var blob = Regex.Match(rest, @"^blob/([^/]+)/(.+)$");
        if (blob.Success) return $"{rawBase}{owner}/{repo}/{blob.Groups[1].Value}/{blob.Groups[2].Value}";
        var tree = Regex.Match(rest, @"^tree/([^/]+)(?:/(.*))?$");
        if (tree.Success)
        {
            var sub = (tree.Groups[2].Value).Trim('/');
            var path = sub.Length == 0 ? "SKILL.md" : $"{sub}/SKILL.md";
            return $"{rawBase}{owner}/{repo}/{tree.Groups[1].Value}/{path}";
        }
        var raw = Regex.Match(rest, @"^raw/([^/]+)/(.+)$");
        if (raw.Success) return $"{rawBase}{owner}/{repo}/{raw.Groups[1].Value}/{raw.Groups[2].Value}";
        if (rest.Length == 0) return $"{rawBase}{owner}/{repo}/HEAD/SKILL.md";
        throw new InvalidOperationException("请指向 SKILL.md 文件或仓库目录");
    }

    private static async Task<string> DownloadAsync(string url, CancellationToken ct)
    {
        using var req = new HttpRequestMessage(HttpMethod.Get, url);
        req.Headers.Add("Accept", "text/plain, text/markdown, */*");
        req.Headers.Add("User-Agent", "AzCode-Windows");
        using var resp = await Http.SendAsync(req, ct);
        if (!resp.IsSuccessStatusCode)
            throw new InvalidOperationException($"下载失败（HTTP {(int)resp.StatusCode}）");
        var text = await resp.Content.ReadAsStringAsync(ct);
        if (text.Trim().Length == 0) throw new InvalidOperationException("技能文件内容为空");
        return text;
    }

    private static (string Name, string Description, string Content) Parse(string raw)
    {
        var text = raw.Replace("\r\n", "\n");
        var name = "";
        var description = "";
        var body = text;

        var fm = Regex.Match(text, @"^---\n([\s\S]*?)\n---\n?([\s\S]*)$");
        if (fm.Success)
        {
            var front = fm.Groups[1].Value;
            body = fm.Groups[2].Value;
            var n = Regex.Match(front, @"(?m)^name:\s*(.+)$");
            if (n.Success) name = n.Groups[1].Value.Trim().Trim('"', '\'');
            var d = Regex.Match(front, @"(?m)^description:\s*(.+)$");
            if (d.Success) description = d.Groups[1].Value.Trim().Trim('"', '\'');
        }
        if (name.Length == 0)
        {
            var h = Regex.Match(body, @"(?m)^#\s+(.+)$");
            if (h.Success) name = h.Groups[1].Value.Trim();
        }
        if (name.Length == 0) name = "未命名技能";
        if (description.Length == 0)
        {
            description = body.Split('\n')
                .Select(l => l.Trim())
                .FirstOrDefault(l => l.Length > 0 && !l.StartsWith('#')) ?? "";
            description = description.Truncate(120);
        }
        var content = body.Trim();
        if (content.Length == 0) throw new InvalidOperationException("技能正文为空");
        return (name, description, content);
    }
}

internal static class StringExtensions
{
    public static string Truncate(this string s, int max) => s.Length <= max ? s : s[..max];
}

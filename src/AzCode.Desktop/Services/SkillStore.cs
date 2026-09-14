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
/// 内置 ponytail（拒绝过度设计）与 impeccable（界面打磨）随应用提供。
/// </summary>
public sealed class SkillStore
{
    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(25) };
    private static readonly JsonSerializerOptions Options = new() { WriteIndented = true };

    public const string PonytailId = "builtin-ponytail";

    /// <summary>内置 impeccable 技能 id。</summary>
    public const string ImpeccableId = "builtin-impeccable";

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

    /// <summary>
    /// 内置 impeccable：移植自 impeccable（Apache-2.0，https://github.com/pbakaus/impeccable）。
    /// 原技能为 Web/原生界面打磨工作流，此处按 AzCode 桌面原生场景提炼为可直接注入系统提示词的版本。
    /// </summary>
    public const string ImpeccableContent = """
        # impeccable · 界面打磨

        你是资深设计总监，目标是把界面做到「值得被点名」的完成度：生产级实现、明确观点、厚待用户、讲究的细节。面向桌面原生界面（WPF）。

        ## 先证据，后动手
        1. 先取真实参考再改代码：拿到用户指定的参考产品/截图后，提取其真实色值、圆角、间距、字级；禁止凭记忆猜色值。
        2. 分清「精修」与「重做」：精修保留既有识别、行为与文案；重做只保留产品事实与功能，把旧外观当反面参照。
        3. 方向未定前不改 UI；方向确认后一口气做完。

        ## 必须达标（对着成品核验）
        - 对比度：正文与占位文字至少 4.5:1，大号文字至少 3:1；彩色底上的次要文字用同色系加深，不要用灰。
        - 层次：阴影必须有偏移加柔和模糊；零偏移的彩色光晕只是装饰。
        - 间距：同组紧凑、组间宽松；标题上方的留白大于下方。
        - 字体：标题与正文有明确的字号与字重台阶，字号随系统设置缩放。
        - 状态：按压、悬停、禁用、加载、错误、空态齐备；控件真的可用。
        - 文案：控件名说清动作，错误信息说清问题与恢复方式。

        ## 明确拒绝（白给时不要用）
        - 用「同尺寸卡片 + 图标 + 标题 + 文字」当页面骨架；卡片嵌套一律错。
        - 标题上方的 kicker/eyebrow 小标签。
        - 01/02/03 章节编号，除非顺序本身携带信息。
        - 渐变文字、装饰性玻璃模糊、超过 1dp 的彩色左边框、硬偏移阴影。
        - 用 emoji 或 Unicode 字符冒充图标；图标应来自统一描边粗细的图标体系。
        - 按品类而不是使用场景决定明暗主题。

        ## 交付纪律
        - 不做开放式反复自检：构建完整、批量检查一次、一轮修完、至多再确认一轮即停。
        - 报告说明改了哪些文件、是否新增依赖或文件。
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
        var seeded = All().Select(s => s.Id).ToHashSet();
        if (seeded.Add(PonytailId))
        {
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
        if (seeded.Add(ImpeccableId))
        {
            Save(new Skill
            {
                Id = ImpeccableId,
                Name = "impeccable · 界面打磨",
                Description = "先取真实参考，再按工艺底线把界面做到生产级完成度。",
                Content = ImpeccableContent.Trim(),
                Source = "",
                Enabled = true,
            });
        }
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
        new Plugin
        {
            Id = "impeccable",
            Name = "impeccable · 界面打磨",
            Description = "把界面做到生产级完成度：先取真实参考，再按工艺底线逐项核验，拒绝廉价设计套路。",
            InstallUrl = "pbakaus/impeccable",
            Tags = new() { "设计", "界面", "打磨" },
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

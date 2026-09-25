using System.IO;
using System.Text.Json;

namespace AzCode.Desktop.Models;

/// <summary>
/// 应用配置。持久化到 %APPDATA%\AzCode\config.json。
/// API Key 可直接填此文件，也可用环境变量 AZCODE_DEEPSEEK_API_KEY（优先）。
/// </summary>
public sealed class AppConfig
{
    public string DeepSeekApiKey { get; set; } = "";
    public string DeepSeekBaseUrl { get; set; } = "https://api.deepseek.com/v1";
    public string Model { get; set; } = "deepseek-chat";
    public int MaxSteps { get; set; } = 0; // 0 表示无限步数，由重复检测兜底
    public double Temperature { get; set; } = 0.2;

    /// <summary>界面主题：light / dark。</summary>
    public string Theme { get; set; } = "light";

    /// <summary>用户自定义系统提示词；留空使用内置默认人设（稳定前缀）。</summary>
    public string SystemPrompt { get; set; } = "";

    /// <summary>GitHub Personal Access Token（用户自己的凭据，仅存本机配置）。</summary>
    public string GitHubToken { get; set; } = "";

    /// <summary>默认仓库，形如 owner/repo；留空则在调用时指定。</summary>
    public string GitHubDefaultRepo { get; set; } = "";

    /// <summary>默认分支；留空表示使用仓库默认分支。</summary>
    public string GitHubDefaultBranch { get; set; } = "";

    /// <summary>校验通过后缓存的登录名，仅用于界面展示。</summary>
    public string GitHubLogin { get; set; } = "";

    public bool GitHubConfigured => GitHubToken.Length > 0;

    private static readonly JsonSerializerOptions Options = new()
    {
        WriteIndented = true,
        PropertyNameCaseInsensitive = true,
    };

    public static string ConfigDir => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "AzCode");

    public static string ConfigPath => Path.Combine(ConfigDir, "config.json");

    public string EffectiveApiKey =>
        Environment.GetEnvironmentVariable("AZCODE_DEEPSEEK_API_KEY") is { Length: > 0 } env
            ? env
            : DeepSeekApiKey;

    public static AppConfig Load()
    {
        try
        {
            if (File.Exists(ConfigPath))
            {
                return JsonSerializer.Deserialize<AppConfig>(File.ReadAllText(ConfigPath), Options)
                       ?? new AppConfig();
            }
        }
        catch
        {
            // 配置损坏时回退默认值，不阻断启动
        }
        return new AppConfig();
    }

    public void Save()
    {
        Directory.CreateDirectory(ConfigDir);
        File.WriteAllText(ConfigPath, JsonSerializer.Serialize(this, Options));
    }
}

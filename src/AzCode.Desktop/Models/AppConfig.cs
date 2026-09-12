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
    public int MaxSteps { get; set; } = 25;
    public double Temperature { get; set; } = 0.2;

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

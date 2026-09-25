using System.Globalization;
using System.Windows;
using System.Windows.Controls;
using AzCode.Desktop.Models;
using AzCode.Desktop.Services;

namespace AzCode.Desktop.Pages;

/// <summary>设置页：复刻 Android activity_settings 的分组卡片结构（能力区改为桌面项）。</summary>
public partial class SettingsPage : UserControl
{
    private readonly AppConfig _cfg;
    private readonly SkillStore _skills = new();

    public SettingsPage(AppConfig cfg)
    {
        _cfg = cfg;
        InitializeComponent();
        Loaded += (_, _) => RefreshAll();
        RefreshAll();
    }

    private void RefreshAll()
    {
        TxtKey.Password = _cfg.DeepSeekApiKey;
        TxtBase.Text = _cfg.DeepSeekBaseUrl;
        TxtModel.Text = _cfg.Model;
        TxtTemp.Text = _cfg.Temperature.ToString(CultureInfo.InvariantCulture);
        TxtMaxSteps.Text = _cfg.MaxSteps.ToString();
        TxtPrompt.Text = _cfg.SystemPrompt;
        TxtVersion.Text = "magic " + (System.Reflection.Assembly.GetEntryAssembly()?.GetName().Version?.ToString(3) ?? "0.1.0");

        var all = _skills.All();
        var on = all.Count(s => s.Enabled);
        TxtSkillsValue.Text = $"已启用 {on} / {all.Count}";
        TxtGithubValue.Text = _cfg.GitHubConfigured ? $"已连接 @{_cfg.GitHubLogin}" : "未连接，点此填写 Token";

        var dark = ThemeService.Current == AppThemeMode.Dark;
        ChipLight.IsChecked = !dark;
        ChipDark.IsChecked = dark;
    }

    private void BtnSave_Click(object sender, RoutedEventArgs e)
    {
        _cfg.DeepSeekApiKey = TxtKey.Password.Trim();
        if (Uri.CheckHostName(TxtBase.Text.Trim()) != UriHostNameType.Unknown)
            _cfg.DeepSeekBaseUrl = TxtBase.Text.Trim();
        _cfg.Model = TxtModel.Text.Trim();
        if (double.TryParse(TxtTemp.Text.Trim(), NumberStyles.Float, CultureInfo.InvariantCulture, out var temp) && temp is >= 0 and <= 2)
            _cfg.Temperature = temp;
        _cfg.MaxSteps = int.TryParse(TxtMaxSteps.Text.Trim(), out var steps) && steps >= 0 ? steps : 0;
        _cfg.SystemPrompt = TxtPrompt.Text.Trim();
        _cfg.Save();

        TxtStatus.Text = $"配置已保存：{AppConfig.ConfigPath}";
        RefreshAll();
    }

    private void ThemeLight_Click(object sender, RoutedEventArgs e)
    {
        ThemeService.Apply(AppThemeMode.Light);
        RefreshAll();
    }

    private void ThemeDark_Click(object sender, RoutedEventArgs e)
    {
        ThemeService.Apply(AppThemeMode.Dark);
        RefreshAll();
    }

    private void OpenSkills_Click(object sender, RoutedEventArgs e) => (Window.GetWindow(this) as MainWindow)?.ShowPage("skills");

    private void OpenGithub_Click(object sender, RoutedEventArgs e) => (Window.GetWindow(this) as MainWindow)?.ShowPage("github");
}

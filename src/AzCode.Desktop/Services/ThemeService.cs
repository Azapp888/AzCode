using System.Windows;
using AzCode.Desktop.Models;

namespace AzCode.Desktop.Services;

public enum AppThemeMode
{
    Light,
    Dark,
}

/// <summary>
/// 运行时主题切换：交换 App.Resources.MergedDictionaries[0] 的令牌字典，
/// 所有颜色均以 DynamicResource 令牌引用，因此界面即时跟随。
/// </summary>
public static class ThemeService
{
    private const int ThemeIndex = 0;

    private static readonly Uri LightUri = new("/Resources/Theme/Light.xaml", UriKind.Relative);
    private static readonly Uri DarkUri = new("/Resources/Theme/Dark.xaml", UriKind.Relative);

    public static AppThemeMode Current { get; private set; } = AppThemeMode.Light;

    public static event Action<AppThemeMode>? Changed;

    public static void Apply(AppThemeMode mode, bool save = true)
    {
        var dicts = Application.Current.Resources.MergedDictionaries;
        if (dicts.Count > ThemeIndex)
            dicts[ThemeIndex] = new ResourceDictionary { Source = mode == AppThemeMode.Dark ? DarkUri : LightUri };

        Current = mode;
        Changed?.Invoke(mode);

        if (!save) return;
        var cfg = AppConfig.Load();
        cfg.Theme = mode == AppThemeMode.Dark ? "dark" : "light";
        cfg.Save();
    }

    public static void ApplyFromConfig(AppConfig cfg)
    {
        Current = cfg.Theme == "dark" ? AppThemeMode.Dark : AppThemeMode.Light;
        Apply(Current, save: false);
    }

    public static void Toggle() => Apply(Current == AppThemeMode.Dark ? AppThemeMode.Light : AppThemeMode.Dark);
}

using System.Windows;
using AzCode.Desktop.Models;
using AzCode.Desktop.Services;

namespace AzCode.Desktop;

public partial class App : Application
{
    public static AppConfig Config { get; private set; } = new();

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        Config = AppConfig.Load();
        // 首次运行写入内置技能（幂等）。
        new SkillStore().SeedBuiltins();
        ThemeService.ApplyFromConfig(Config);

        var window = new MainWindow();
        MainWindow = window;
        window.Show();
    }
}

using System.Windows;
using AzCode.Desktop.Services;

namespace AzCode.Desktop;

public partial class App : Application
{
    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        // 首次运行写入内置 ponytail 技能（幂等）。
        new SkillStore().SeedBuiltins();
    }
}

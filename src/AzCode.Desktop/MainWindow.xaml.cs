using System.Windows;
using System.Windows.Controls;
using AzCode.Desktop.Models;
using AzCode.Desktop.Pages;
using AzCode.Desktop.Services;

namespace AzCode.Desktop;

/// <summary>
/// 桌面端外壳：自绘标题栏 + 左侧常驻导航 + 右侧内容区。
/// 对应 Android 端的 DrawerLayout + 各 Activity，桌面改用导航切换内容区。
/// </summary>
public partial class MainWindow : Window
{
    private readonly AppConfig _cfg = App.Config;
    private readonly Dictionary<string, UserControl> _pages = new();
    private bool _ready;

    public MainWindow()
    {
        InitializeComponent();
        ThemeService.Changed += mode => Dispatcher.Invoke(() => TxtTheme.Text = mode == AppThemeMode.Dark ? "亮色" : "暗色");
        TxtTheme.Text = ThemeService.Current == AppThemeMode.Dark ? "亮色" : "暗色";
        _ready = true;
        Navigate("chat");
    }

    private void Nav_Checked(object sender, RoutedEventArgs e)
    {
        if (!_ready || sender is not FrameworkElement el) return;
        Navigate(el.Tag as string ?? "chat");
    }

    /// <summary>供子页面请求切换导航（如设置页跳转技能 / GitHub）。</summary>
    public void ShowPage(string tag)
    {
        switch (tag)
        {
            case "skills": NavSkills.IsChecked = true; break;
            case "github": NavGithub.IsChecked = true; break;
            case "settings": NavSettings.IsChecked = true; break;
            default: NavChat.IsChecked = true; break;
        }
    }

    /// <summary>输入区模型 chip 的跳转目标。</summary>
    public void ShowSettings() => ShowPage("settings");

    private void Navigate(string tag)
    {
        if (!_pages.TryGetValue(tag, out var page))
        {
            page = tag switch
            {
                "skills" => new SkillsPage(_cfg),
                "github" => new GithubPage(_cfg),
                "settings" => new SettingsPage(_cfg),
                _ => new ChatPage(_cfg),
            };
            _pages[tag] = page;
        }
        ContentHost.Content = page;
    }

    private void BtnNewChat_Click(object sender, RoutedEventArgs e)
    {
        NavChat.IsChecked = true;
        Navigate("chat");
        if (_pages["chat"] is ChatPage chat) chat.NewConversation();
    }

    private void BtnTheme_Click(object sender, RoutedEventArgs e) => ThemeService.Toggle();

    private void BtnMin_Click(object sender, RoutedEventArgs e) => WindowState = WindowState.Minimized;

    private void BtnMax_Click(object sender, RoutedEventArgs e) =>
        WindowState = WindowState == WindowState.Maximized ? WindowState.Normal : WindowState.Maximized;

    private void BtnClose_Click(object sender, RoutedEventArgs e) => Close();
}

using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Shapes;
using AzCode.Desktop.Models;
using AzCode.Desktop.Services;

namespace AzCode.Desktop.Pages;

/// <summary>GitHub 接入页：复刻 Android activity_github 的状态卡 + 表单 + 三个动作按钮。</summary>
public partial class GithubPage : UserControl
{
    private readonly AppConfig _cfg;

    public GithubPage(AppConfig cfg)
    {
        _cfg = cfg;
        InitializeComponent();
        Loaded += (_, _) => Refresh();
        Refresh();
    }

    private void Refresh()
    {
        TxtToken.Password = _cfg.GitHubToken;
        TxtRepo.Text = _cfg.GitHubDefaultRepo;
        TxtBranch.Text = _cfg.GitHubDefaultBranch;

        if (_cfg.GitHubConfigured)
        {
            DotStatus.SetResourceReference(Shapes.Shape.FillProperty, "Status.On");
            var branch = string.IsNullOrWhiteSpace(_cfg.GitHubDefaultBranch) ? "仓库默认" : _cfg.GitHubDefaultBranch;
            var repo = string.IsNullOrWhiteSpace(_cfg.GitHubDefaultRepo) ? "未设置" : _cfg.GitHubDefaultRepo;
            TxtHeader.Text = $"已连接：@{_cfg.GitHubLogin}　默认仓库：{repo}　默认分支：{branch}";
            BtnLogout.Visibility = Visibility.Visible;
        }
        else
        {
            DotStatus.SetResourceReference(Shapes.Shape.FillProperty, "Status.Off");
            TxtHeader.Text = "未接入 GitHub。填入 Token 后，Agent 就能读取、提交你仓库中的文件，并管理 Issue 与 Pull Request。";
            BtnLogout.Visibility = Visibility.Collapsed;
        }
    }

    private async void Save_Click(object sender, RoutedEventArgs e)
    {
        var token = TxtToken.Password.Trim();
        if (token.Length == 0)
        {
            TxtMsg.Text = "请先填写 Token。";
            return;
        }

        BtnSave.IsEnabled = false;
        TxtMsg.Text = "正在验证 Token…";
        try
        {
            var user = await GitHubClient.WhoAmIAsync(token, CancellationToken.None);
            _cfg.GitHubToken = token;
            _cfg.GitHubDefaultRepo = ReadOrKeep(TxtRepo.Text, _cfg.GitHubDefaultRepo);
            _cfg.GitHubDefaultBranch = ReadOrKeep(TxtBranch.Text, _cfg.GitHubDefaultBranch);
            _cfg.GitHubLogin = user["login"]?.ToString() ?? "";
            _cfg.Save();
            TxtMsg.Text = "已保存并验证：@" + _cfg.GitHubLogin;
            Refresh();
        }
        catch (Exception ex)
        {
            TxtMsg.Text = "验证失败：" + ex.Message;
        }
        finally
        {
            BtnSave.IsEnabled = true;
        }
    }

    private async void Verify_Click(object sender, RoutedEventArgs e)
    {
        var token = TxtToken.Password.Trim();
        if (token.Length == 0)
        {
            TxtMsg.Text = "请先填写 Token。";
            return;
        }

        BtnVerify.IsEnabled = false;
        TxtMsg.Text = "正在连接…";
        try
        {
            var user = await GitHubClient.WhoAmIAsync(token, CancellationToken.None);
            TxtMsg.Text = "连接正常：@" + (user["login"]?.ToString() ?? "");
        }
        catch (Exception ex)
        {
            TxtMsg.Text = "验证失败：" + ex.Message;
        }
        finally
        {
            BtnVerify.IsEnabled = true;
        }
    }

    private void Logout_Click(object sender, RoutedEventArgs e)
    {
        _cfg.GitHubToken = "";
        _cfg.GitHubLogin = "";
        _cfg.Save();
        TxtMsg.Text = "已断开 GitHub 连接。";
        Refresh();
    }

    private static string ReadOrKeep(string input, string fallback) =>
        string.IsNullOrWhiteSpace(input) ? fallback : input.Trim();
}

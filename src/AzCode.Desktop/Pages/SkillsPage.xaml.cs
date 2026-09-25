using System.Windows;
using System.Windows.Controls;
using AzCode.Desktop.Models;
using AzCode.Desktop.Services;

namespace AzCode.Desktop.Pages;

/// <summary>技能页：复刻 Android activity_skills 的开关 + 描述 + 来源结构。</summary>
public partial class SkillsPage : UserControl
{
    private readonly AppConfig _cfg;
    private readonly SkillStore _store = new();

    public SkillsPage(AppConfig cfg)
    {
        _cfg = cfg;
        InitializeComponent();
        Refresh();
    }

    private void Refresh()
    {
        var rows = _store.All().Select(sk => new SkillRow(sk)).ToList();
        SkillList.ItemsSource = rows;
        TxtEmpty.Visibility = rows.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    private void ToggleSkill_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not CheckBox box || box.DataContext is not SkillRow row) return;
        _store.SetEnabled(row.Skill.Id, box.IsChecked == true);
        Refresh();
    }

    private void DeleteSkill_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not Button btn || btn.DataContext is not SkillRow wr) return;
        var skill = wr.Skill;
        if (skill.Id.StartsWith("builtin-", StringComparison.Ordinal))
        {
            SetStatus("内置技能会在下次启动时自动恢复，改为「停用」即可关闭。");
            return;
        }
        _store.Remove(skill.Id);
        Refresh();
        SetStatus("已删除：" + skill.Name);
    }

    private void SaveSkill_Click(object sender, RoutedEventArgs e)
    {
        var name = TxtSkillName.Text.Trim();
        var content = TxtSkillContent.Text.Trim();
        if (name.Length == 0)
        {
            SetStatus("请填写技能名称。");
            return;
        }
        if (content.Length == 0)
        {
            SetStatus("请填写提示词内容。");
            return;
        }

        _store.Save(new Skill
        {
            Id = "custom-" + Guid.NewGuid().ToString("N")[..8],
            Name = name,
            Description = TxtSkillDesc.Text.Trim(),
            Content = content,
            Source = "自定义",
            Enabled = true,
        });

        TxtSkillName.Clear();
        TxtSkillDesc.Clear();
        TxtSkillContent.Clear();
        BtnNewSkill.IsChecked = false;
        Refresh();
        SetStatus("已创建：" + name);
    }

    private void CancelNew_Click(object sender, RoutedEventArgs e) => BtnNewSkill.IsChecked = false;

    private async void Install_Click(object sender, RoutedEventArgs e)
    {
        var url = TxtInstallUrl.Text.Trim();
        if (url.Length == 0)
        {
            SetStatus("请先填写 SKILL.md 链接或仓库地址。");
            return;
        }

        BtnInstall.IsEnabled = false;
        SetStatus("正在下载并解析技能…");
        try
        {
            var skill = await _store.InstallAsync(url, CancellationToken.None, _cfg.GitHubToken);
            TxtInstallUrl.Clear();
            Refresh();
            SetStatus("已安装：" + skill.Name);
        }
        catch (Exception ex)
        {
            SetStatus("安装失败：" + ex.Message);
        }
        finally
        {
            BtnInstall.IsEnabled = true;
        }
    }

    private void SetStatus(string text) => TxtMsg.Text = text;
}

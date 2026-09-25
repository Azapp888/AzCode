using System.ComponentModel;
using AzCode.Desktop.Models;

namespace AzCode.Desktop.Pages;

/// <summary>技能列表行视图模型：附带「详情」展开态，避免在代码里翻找模板子元素。</summary>
public sealed class SkillRow : INotifyPropertyChanged
{
    private bool _showDetail;

    public SkillRow(Skill skill) => Skill = skill;

    public Skill Skill { get; }

    public bool ShowDetail
    {
        get => _showDetail;
        set
        {
            if (_showDetail == value) return;
            _showDetail = value;
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(ShowDetail)));
        }
    }

    public string Title => Skill.Name;

    public event PropertyChangedEventHandler? PropertyChanged;
}

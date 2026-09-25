using System.ComponentModel;
using System.Runtime.CompilerServices;

namespace AzCode.Desktop.Models;

/// <summary>界面上的一条消息，对应 Android 的 item_msg_* 布局类型。</summary>
public enum ChatKind
{
    Welcome,
    User,
    Assistant,
    System,
    Error,
    Tool,
    Typing,
}

/// <summary>AgentRunner 向界面推送的结构化事件。</summary>
public enum AgentUiKind
{
    Typing,
    Assistant,
    System,
    Error,
    ToolStart,
    ToolEnd,
}

public sealed record AgentUiEvent(AgentUiKind Kind, string Text, string? Tool = null);

/// <summary>消息气泡的数据项；工具卡支持展开/收起与状态文本变化。</summary>
public sealed class ChatItem : INotifyPropertyChanged
{
    public ChatKind Kind { get; init; }

    /// <summary>正文；工具卡时表示调用参数。</summary>
    public string Text { get; init; } = "";

    public string? ToolName { get; init; }

    /// <summary>附件名等次要说明文字；空字符串表示不显示。</summary>
    public string Note { get; init; } = "";

    private string _status = "执行中";

    public string Status
    {
        get => _status;
        private set { _status = value; OnChanged(); }
    }

    private string _result = "";

    public string Result
    {
        get => _result;
        private set { _result = value; OnChanged(); }
    }

    private bool _expanded;

    public bool Expanded
    {
        get => _expanded;
        set { if (_expanded != value) { _expanded = value; OnChanged(); } }
    }

    public void Complete(string result)
    {
        Result = result;
        Status = "完成";
    }

    public void Fail(string result)
    {
        Result = result;
        Status = "失败";
    }

    public event PropertyChangedEventHandler? PropertyChanged;

    private void OnChanged([CallerMemberName] string? name = null) =>
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
}

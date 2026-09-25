using System.Collections.ObjectModel;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using AzCode.Desktop.Models;
using AzCode.Desktop.Services;

namespace AzCode.Desktop.Pages;

/// <summary>
/// 主对话页：复刻 Android activity_main 的气泡式消息流 + 玻璃输入区。
/// 首版以纯文本渲染 Markdown 内容（公式与代码块降级为等宽文本）。
/// </summary>
public partial class ChatPage : UserControl
{
    private static readonly JsonSerializerOptions Pretty = new() { WriteIndented = true };

    private static readonly Dictionary<string, string> ToolLabels = new()
    {
        ["get_screen"] = "读取屏幕",
        ["click"] = "点击",
        ["type"] = "输入文字",
        ["key"] = "按键",
        ["shell"] = "执行命令",
        ["search_plugins"] = "搜索插件",
        ["install_plugin"] = "安装插件",
        ["list_installed_plugins"] = "列出插件",
        ["set_plugin_enabled"] = "切换插件",
        ["remove_plugin"] = "移除插件",
        ["finish"] = "结束任务",
    };

    private readonly AppConfig _cfg;
    private readonly ObservableCollection<ChatItem> _items = new();
    private readonly Dictionary<string, ChatItem> _openTools = new();
    private AgentRunner? _runner;
    private CancellationTokenSource? _cts;
    private ChatItem? _typing;

    public ChatPage(AppConfig cfg)
    {
        _cfg = cfg;
        InitializeComponent();
        ChatList.ItemsSource = _items;
        TxtModel.Text = string.IsNullOrWhiteSpace(cfg.Model) ? "未配置模型" : cfg.Model;
        // 设置页改完模型后切回对话页要同步 chip 文案。
        Loaded += (_, _) => TxtModel.Text = string.IsNullOrWhiteSpace(_cfg.Model) ? "未配置模型" : _cfg.Model;
        LoadHistory();
    }

    /// <summary>清空当前会话，回到欢迎态。</summary>
    public void NewConversation()
    {
        ConversationStore.Clear();
        _items.Clear();
        _typing = null;
        _openTools.Clear();
        ShowWelcome();
    }

    private void LoadHistory()
    {
        foreach (var msg in ConversationStore.Load())
        {
            switch (msg.Role)
            {
                case "user":
                    _items.Add(new ChatItem { Kind = ChatKind.User, Text = msg.Content ?? "" });
                    break;
                case "assistant":
                    if (!string.IsNullOrWhiteSpace(msg.Content))
                        _items.Add(new ChatItem { Kind = ChatKind.Assistant, Text = msg.Content! });
                    foreach (var call in msg.ToolCalls ?? new List<ToolCall>())
                    {
                        var item = NewToolItem(call.Function.Name, call.Function.Arguments);
                        _items.Add(item);
                        _openTools[call.Id] = item;
                    }
                    break;
                case "tool":
                    if (msg.ToolCallId is not null && _openTools.Remove(msg.ToolCallId, out var tool))
                        FinishTool(tool, msg.Content ?? "");
                    break;
            }
        }

        if (_items.Count == 0) ShowWelcome();
        ScrollToEnd();
    }

    private void ShowWelcome() => _items.Add(new ChatItem { Kind = ChatKind.Welcome });

    private void RemoveWelcome()
    {
        for (var i = _items.Count - 1; i >= 0; i--)
            if (_items[i].Kind == ChatKind.Welcome)
                _items.RemoveAt(i);
    }

    private void TxtTask_TextChanged(object sender, TextChangedEventArgs e) =>
        TxtTaskHint.Visibility = string.IsNullOrEmpty(TxtTask.Text) ? Visibility.Visible : Visibility.Collapsed;

    private void TxtTask_PreviewKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Return && Keyboard.Modifiers != ModifierKeys.Shift)
        {
            e.Handled = true;
            Send();
        }
    }

    private void BtnSend_Click(object sender, RoutedEventArgs e) => Send();

    private void BtnStop_Click(object sender, RoutedEventArgs e) => _cts?.Cancel();

    private void BtnModel_Click(object sender, RoutedEventArgs e)
    {
        BtnModel.IsChecked = false;
        (Window.GetWindow(this) as MainWindow)?.ShowSettings();
    }

    private async void Send()
    {
        if (_runner is not null) return;

        var task = TxtTask.Text.Trim();
        if (task.Length == 0)
        {
            Add(new ChatItem { Kind = ChatKind.System, Text = "请输入任务描述。" });
            return;
        }

        if (string.IsNullOrWhiteSpace(_cfg.EffectiveApiKey))
        {
            Add(new ChatItem { Kind = ChatKind.Error, Text = "未配置 DeepSeek API Key，请先在「设置」中填写。" });
            return;
        }

        TxtTask.Clear();
        RemoveWelcome();
        Add(new ChatItem { Kind = ChatKind.User, Text = task });

        _cfg.Model = string.IsNullOrWhiteSpace(_cfg.Model) ? TxtModel.Text : _cfg.Model;
        _cts = new CancellationTokenSource();
        _runner = new AgentRunner(_cfg);
        _runner.UiEvent += OnUiEvent;
        SetRunning(true);

        try
        {
            await _runner.RunAsync(task, _cts.Token);
        }
        catch (OperationCanceledException)
        {
            Add(new ChatItem { Kind = ChatKind.System, Text = "任务已停止。" });
        }
        catch (Exception ex)
        {
            Add(new ChatItem { Kind = ChatKind.Error, Text = "任务失败：" + ex.Message });
        }
        finally
        {
            if (_runner is not null) _runner.UiEvent -= OnUiEvent;
            _runner = null;
            _cts?.Dispose();
            _cts = null;
            RemoveTyping();
            SetRunning(false);
        }
    }

    private void OnUiEvent(AgentUiEvent ev) => Dispatcher.Invoke(() =>
    {
        switch (ev.Kind)
        {
            case AgentUiKind.Typing:
                ShowTyping();
                break;

            case AgentUiKind.Assistant:
                RemoveTyping();
                Add(new ChatItem { Kind = ChatKind.Assistant, Text = ev.Text });
                break;

            case AgentUiKind.System:
                RemoveTyping();
                Add(new ChatItem { Kind = ChatKind.System, Text = ev.Text });
                break;

            case AgentUiKind.Error:
                RemoveTyping();
                Add(new ChatItem { Kind = ChatKind.Error, Text = ev.Text });
                break;

            case AgentUiKind.ToolStart:
                RemoveTyping();
                Add(NewToolItem(ev.Tool ?? "tool", ev.Text, run: true));
                break;

            case AgentUiKind.ToolEnd:
                if (_pending is { } item)
                {
                    FinishTool(item, ev.Text);
                    _pending = null;
                }
                break;
        }
    });

    private ChatItem? _pending;

    private ChatItem NewToolItem(string name, string args, bool run = false)
    {
        var item = new ChatItem
        {
            Kind = ChatKind.Tool,
            ToolName = Label(name),
            Text = PrettyJson(args),
        };
        if (run) _pending = item;
        return item;
    }

    private static void FinishTool(ChatItem item, string result)
    {
        var text = PrettyJson(result);
        if (result.Contains("\"ok\":false")) item.Fail(text);
        else item.Complete(text);
    }

    private static string Label(string name) =>
        ToolLabels.TryGetValue(name, out var label) ? label : name;

    private static string PrettyJson(string raw)
    {
        if (string.IsNullOrWhiteSpace(raw)) return "";
        try
        {
            var node = JsonNode.Parse(raw);
            return node is null ? raw : node.ToJsonString(Pretty);
        }
        catch (JsonException)
        {
            return raw;
        }
    }

    private void ShowTyping()
    {
        if (_typing is not null) return;
        _typing = new ChatItem { Kind = ChatKind.Typing };
        _items.Add(_typing);
        ScrollToEnd();
    }

    private void RemoveTyping()
    {
        if (_typing is null) return;
        _items.Remove(_typing);
        _typing = null;
    }

    private void Add(ChatItem item)
    {
        _items.Add(item);
        ScrollToEnd();
    }

    private void ScrollToEnd()
    {
        if (_items.Count == 0) return;
        ChatList.ScrollIntoView(_items[^1]);
    }

    private void SetRunning(bool running)
    {
        BtnSend.Visibility = running ? Visibility.Collapsed : Visibility.Visible;
        BtnStop.Visibility = running ? Visibility.Visible : Visibility.Collapsed;
    }
}

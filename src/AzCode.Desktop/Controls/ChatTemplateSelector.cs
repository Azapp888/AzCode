using System.Windows;
using System.Windows.Controls;
using AzCode.Desktop.Models;

namespace AzCode.Desktop.Controls;

/// <summary>按消息类型挑选气泡模板，模板资源定义在 Resources/Controls/Messages.xaml。</summary>
public sealed class ChatTemplateSelector : DataTemplateSelector
{
    public override DataTemplate SelectTemplate(object item, DependencyObject container)
    {
        var key = item switch
        {
            ChatItem { Kind: ChatKind.User } => "Chat.User",
            ChatItem { Kind: ChatKind.System } => "Chat.System",
            ChatItem { Kind: ChatKind.Error } => "Chat.Error",
            ChatItem { Kind: ChatKind.Tool } => "Chat.Tool",
            ChatItem { Kind: ChatKind.Typing } => "Chat.Typing",
            ChatItem { Kind: ChatKind.Welcome } => "Chat.Welcome",
            _ => "Chat.Assistant",
        };
        var tpl = (container as FrameworkElement)?.TryFindResource(key) as DataTemplate
                  ?? Application.Current.TryFindResource(key) as DataTemplate
                  ?? Application.Current.TryFindResource("Chat.Assistant") as DataTemplate;
        return tpl ?? base.SelectTemplate(item, container)!;
    }
}

using System.IO;
using System.Text.Json;
using AzCode.Desktop.Models;

namespace AzCode.Desktop.Services;

/// <summary>
/// 会话历史持久化（config 目录下 conversation.json）。
/// 历史以 append-only 方式增长，配合 <see cref="PromptCache"/> 保持前缀缓存温热；
/// 仅在过长时从最近的 user 边界裁剪，保证 tool 调用与结果配对完整。
/// </summary>
public static class ConversationStore
{
    private const int MaxMessages = 60;

    private static readonly JsonSerializerOptions Options = new() { WriteIndented = false };

    private static string Path => System.IO.Path.Combine(AppConfig.ConfigDir, "conversation.json");

    public static List<ChatMessage> Load()
    {
        try
        {
            if (File.Exists(Path))
                return JsonSerializer.Deserialize<List<ChatMessage>>(File.ReadAllText(Path), Options) ?? new();
        }
        catch
        {
            // 文件损坏时从空历史开始
        }
        return new List<ChatMessage>();
    }

    public static void Save(List<ChatMessage> history)
    {
        try
        {
            Directory.CreateDirectory(AppConfig.ConfigDir);
            File.WriteAllText(Path, JsonSerializer.Serialize(Trim(history), Options));
        }
        catch
        {
            // 持久化失败不阻断任务
        }
    }

    public static void Clear()
    {
        try
        {
            if (File.Exists(Path)) File.Delete(Path);
        }
        catch
        {
            // 忽略
        }
    }

    private static List<ChatMessage> Trim(List<ChatMessage> history)
    {
        if (history.Count <= MaxMessages) return history;
        for (var i = history.Count - MaxMessages; i < history.Count; i++)
            if (history[i].Role is "user" or "system")
                return history.Skip(i).ToList();
        return history.Skip(history.Count - MaxMessages).ToList();
    }
}

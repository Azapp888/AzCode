using System.Collections.Generic;
using System.Text.Json.Serialization;

namespace AzCode.Desktop.Models;

/// <summary>OpenAI 兼容的对话消息。</summary>
public sealed class ChatMessage
{
    [JsonPropertyName("role")] public string Role { get; set; } = "user";

    [JsonPropertyName("content")] public string? Content { get; set; }

    [JsonPropertyName("tool_calls")] public List<ToolCall>? ToolCalls { get; set; }

    [JsonPropertyName("tool_call_id")] public string? ToolCallId { get; set; }
}

public sealed class ToolCall
{
    [JsonPropertyName("id")] public string Id { get; set; } = "";

    [JsonPropertyName("type")] public string Type { get; set; } = "function";

    [JsonPropertyName("function")] public FunctionCall Function { get; set; } = new();
}

public sealed class FunctionCall
{
    [JsonPropertyName("name")] public string Name { get; set; } = "";

    [JsonPropertyName("arguments")] public string Arguments { get; set; } = "{}";
}

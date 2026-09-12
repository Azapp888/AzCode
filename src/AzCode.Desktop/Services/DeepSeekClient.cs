using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.Json.Serialization;
using AzCode.Desktop.Models;

namespace AzCode.Desktop.Services;

/// <summary>DeepSeek（OpenAI 兼容）chat/completions 客户端，支持 function calling。</summary>
public sealed class DeepSeekClient
{
    private readonly HttpClient _http = new() { Timeout = TimeSpan.FromMinutes(3) };

    private static readonly JsonSerializerOptions Options = new()
    {
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    };

    public async Task<ChatMessage> ChatAsync(
        AppConfig cfg, List<ChatMessage> messages, JsonArray tools, CancellationToken ct)
    {
        if (string.IsNullOrWhiteSpace(cfg.EffectiveApiKey))
            throw new InvalidOperationException("未配置 DeepSeek API Key（config.json 或环境变量 AZCODE_DEEPSEEK_API_KEY）");

        var body = new JsonObject
        {
            ["model"] = cfg.Model,
            ["messages"] = JsonSerializer.SerializeToNode(messages, Options),
            ["temperature"] = cfg.Temperature,
        };
        if (tools.Count > 0)
        {
            body["tools"] = tools;
            body["tool_choice"] = "auto";
        }

        using var req = new HttpRequestMessage(
            HttpMethod.Post, $"{cfg.DeepSeekBaseUrl.TrimEnd('/')}/chat/completions");
        req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", cfg.EffectiveApiKey);
        req.Content = new StringContent(body.ToJsonString(), Encoding.UTF8, "application/json");

        using var resp = await _http.SendAsync(req, ct);
        var text = await resp.Content.ReadAsStringAsync(ct);
        if (!resp.IsSuccessStatusCode)
            throw new InvalidOperationException($"DeepSeek {(int)resp.StatusCode}: {text}");

        var node = JsonNode.Parse(text) ?? throw new InvalidOperationException("空响应");
        var message = node["choices"]?[0]?["message"]
                      ?? throw new InvalidOperationException($"响应缺少 choices: {text}");
        return message.Deserialize<ChatMessage>(Options) ?? new ChatMessage();
    }
}

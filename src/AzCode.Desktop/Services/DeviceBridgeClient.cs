using System.Net.Http;
using System.Text;
using System.Text.Json;

namespace AzCode.Desktop.Services;

/// <summary>Android 能力桥（经 adb forward 暴露在本机 127.0.0.1:port）的 HTTP 客户端。</summary>
public sealed class DeviceBridgeClient
{
    private readonly HttpClient _http;

    public DeviceBridgeClient(int port)
    {
        _http = new HttpClient
        {
            BaseAddress = new Uri($"http://127.0.0.1:{port}"),
            Timeout = TimeSpan.FromSeconds(60),
        };
    }

    public async Task<string> GetAsync(string path)
    {
        using var resp = await _http.GetAsync(path);
        return await resp.Content.ReadAsStringAsync();
    }

    public async Task<string> PostJsonAsync(string path, object payload)
    {
        var json = JsonSerializer.Serialize(payload);
        using var content = new StringContent(json, Encoding.UTF8, "application/json");
        using var resp = await _http.PostAsync(path, content);
        return await resp.Content.ReadAsStringAsync();
    }

    public async Task<bool> PingAsync()
    {
        try
        {
            var body = await GetAsync("/health");
            return body.Contains("\"ok\":true");
        }
        catch
        {
            return false;
        }
    }
}

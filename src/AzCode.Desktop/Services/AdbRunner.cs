using System.Diagnostics;

namespace AzCode.Desktop.Services;

/// <summary>调用本机 adb，用于建立端口转发与查询设备。</summary>
public static class AdbRunner
{
    public static async Task<string> RunAsync(string adbPath, string args)
    {
        var psi = new ProcessStartInfo(adbPath, args)
        {
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true,
        };
        using var p = Process.Start(psi);
        if (p is null) return "adb 启动失败";
        var stdout = await p.StandardOutput.ReadToEndAsync();
        var stderr = await p.StandardError.ReadToEndAsync();
        await p.WaitForExitAsync();
        return (stdout + stderr).Trim();
    }

    public static Task<string> DevicesAsync(string adbPath) => RunAsync(adbPath, "devices");

    public static Task<string> ForwardAsync(string adbPath, int port) =>
        RunAsync(adbPath, $"forward tcp:{port} tcp:{port}");
}

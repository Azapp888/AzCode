using System.Windows;
using AzCode.Desktop.Models;
using AzCode.Desktop.Services;

namespace AzCode.Desktop;

public partial class MainWindow : Window
{
    private AppConfig _cfg;
    private CancellationTokenSource? _cts;
    private DeviceBridgeClient? _bridge;

    public MainWindow()
    {
        InitializeComponent();
        _cfg = AppConfig.Load();
        TxtAdb.Text = _cfg.AdbPath;
        TxtPort.Text = _cfg.BridgePort.ToString();
        TxtModel.Text = _cfg.Model;
        TxtKey.Password = _cfg.DeepSeekApiKey;
        TxtStatus.Text = $"配置路径：{AppConfig.ConfigPath}";
    }

    private void BtnSave_Click(object sender, RoutedEventArgs e)
    {
        ReadIntoConfig();
        _cfg.Save();
        AppendLog($"配置已保存到 {AppConfig.ConfigPath}");
    }

    private async void BtnConnect_Click(object sender, RoutedEventArgs e)
    {
        ReadIntoConfig();
        _cfg.Save();
        try
        {
            TxtConn.Text = "连接中…";
            var devices = await AdbRunner.DevicesAsync(_cfg.AdbPath);
            AppendLog($"[adb devices]\n{devices}");
            var forward = await AdbRunner.ForwardAsync(_cfg.AdbPath, _cfg.BridgePort);
            if (!string.IsNullOrWhiteSpace(forward)) AppendLog($"[adb forward] {forward}");

            _bridge = new DeviceBridgeClient(_cfg.BridgePort);
            if (await _bridge.PingAsync())
            {
                TxtConn.Text = "已连接";
                AppendLog(await _bridge.GetAsync("/health"));
            }
            else
            {
                TxtConn.Text = "未连通";
                AppendLog("能力桥无响应：请确认手机上 AzCode Bridge 已启动、无障碍已开启、USB 调试已授权。");
            }
        }
        catch (Exception ex)
        {
            TxtConn.Text = "失败";
            AppendLog($"连接失败：{ex.Message}");
        }
    }

    private async void BtnRun_Click(object sender, RoutedEventArgs e)
    {
        var task = TxtTask.Text.Trim();
        if (task.Length == 0)
        {
            AppendLog("请输入任务描述。");
            return;
        }
        if (string.IsNullOrWhiteSpace(_cfg.EffectiveApiKey))
        {
            AppendLog("未配置 DeepSeek API Key。");
            return;
        }

        _bridge ??= new DeviceBridgeClient(_cfg.BridgePort);
        if (!await _bridge.PingAsync())
        {
            AppendLog("能力桥未连通，请先点「连接设备」。");
            return;
        }

        ReadIntoConfig();
        _cfg.Save();

        _cts = new CancellationTokenSource();
        BtnRun.IsEnabled = false;
        BtnStop.IsEnabled = true;

        var runner = new AgentRunner(_cfg, _bridge);
        runner.Log += AppendLog;
        try
        {
            AppendLog($"=== 任务开始：{task} ===");
            await runner.RunAsync(task, _cts.Token);
        }
        catch (OperationCanceledException)
        {
            AppendLog("任务已取消。");
        }
        catch (Exception ex)
        {
            AppendLog($"任务失败：{ex.Message}");
        }
        finally
        {
            AppendLog("=== 任务结束 ===");
            BtnRun.IsEnabled = true;
            BtnStop.IsEnabled = false;
            _cts?.Dispose();
            _cts = null;
        }
    }

    private void BtnStop_Click(object sender, RoutedEventArgs e)
    {
        _cts?.Cancel();
    }

    private void ReadIntoConfig()
    {
        _cfg.AdbPath = TxtAdb.Text.Trim();
        if (int.TryParse(TxtPort.Text.Trim(), out var port)) _cfg.BridgePort = port;
        _cfg.Model = TxtModel.Text.Trim();
        _cfg.DeepSeekApiKey = TxtKey.Password;
    }

    private void AppendLog(string line)
    {
        Dispatcher.Invoke(() =>
        {
            TxtLog.AppendText(line + Environment.NewLine);
            TxtLog.ScrollToEnd();
        });
    }
}

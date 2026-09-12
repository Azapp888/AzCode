using System.Windows;
using AzCode.Desktop.Models;
using AzCode.Desktop.Services;

namespace AzCode.Desktop;

public partial class MainWindow : Window
{
    private readonly AppConfig _cfg;
    private CancellationTokenSource? _cts;

    public MainWindow()
    {
        InitializeComponent();
        _cfg = AppConfig.Load();
        TxtKey.Password = _cfg.DeepSeekApiKey;
        TxtBase.Text = _cfg.DeepSeekBaseUrl;
        TxtModel.Text = _cfg.Model;
        TxtMaxSteps.Text = _cfg.MaxSteps.ToString();
        TxtStatus.Text = $"配置路径：{AppConfig.ConfigPath}";
    }

    private void BtnSave_Click(object sender, RoutedEventArgs e)
    {
        ReadIntoConfig();
        _cfg.Save();
        AppendLog($"配置已保存到 {AppConfig.ConfigPath}");
    }

    private async void BtnRun_Click(object sender, RoutedEventArgs e)
    {
        var task = TxtTask.Text.Trim();
        if (task.Length == 0)
        {
            AppendLog("请输入任务描述。");
            return;
        }

        ReadIntoConfig();
        _cfg.Save();

        if (string.IsNullOrWhiteSpace(_cfg.EffectiveApiKey))
        {
            AppendLog("未配置 DeepSeek API Key。");
            return;
        }

        _cts = new CancellationTokenSource();
        BtnRun.IsEnabled = false;
        BtnStop.IsEnabled = true;

        var runner = new AgentRunner(_cfg);
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
        _cfg.DeepSeekApiKey = TxtKey.Password;
        _cfg.DeepSeekBaseUrl = TxtBase.Text.Trim();
        _cfg.Model = TxtModel.Text.Trim();
        if (int.TryParse(TxtMaxSteps.Text.Trim(), out var steps) && steps > 0)
            _cfg.MaxSteps = steps;
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

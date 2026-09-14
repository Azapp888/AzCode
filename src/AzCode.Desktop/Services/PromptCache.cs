using AzCode.Desktop.Models;

namespace AzCode.Desktop.Services;

/// <summary>
/// 前缀缓存稳定性（移植自 deepseek-harness 的降缓存未命中设计）：
///  - 第 0 条 system 只放不随任务变化的稳定前缀，字节永久不变；
///  - 技能/插件提示等运行时上下文单独成一条 system 消息；
///  - 内容未变时不重复插入，变化时追加到历史末尾，绝不重写前缀；
///  - 工具定义顺序固定、不随条件增删。
/// </summary>
public static class PromptCache
{
    public const string DefaultSystemPrompt = """
        你是 AzCode，一个运行在 Windows 电脑本地的自动化助手，直接控制这台电脑。
        按需调用 get_screen 观察当前活动窗口的控件树（名称/类型/坐标）与窗口标题，只在需要定位控件时读取，不必每一步都读。
        点击优先用 click 的 text 字段匹配控件名称，匹配不到时再用坐标。
        输入文字用 type；组合键用 key（如 "ctrl+s"、"enter"、"alt+f4"）。
        需要执行系统操作（启动程序、文件操作、查询信息）时用 shell（PowerShell）。
        涉及用户的 GitHub 仓库时先用 github_status 确认接入状态；已接入后可直接读取/提交仓库文件、查看提交记录、创建 Issue 与 Pull Request；未接入时引导用户填写 Token 或用 github_save_config 保存。
        面向用户的文字要简洁、口语化，直接说明你正在做什么或最终结果，不要输出 JSON 或工具参数。
        任务完成或无法继续时，调用 finish 并给出简短总结。
        """;

    private const string PluginHint =
        "【插件市场】用户想扩展能力、寻找插件时，调用 search_plugins 扫描热门开源仓库获取候选，" +
        "再用 install_plugin 一键安装；用 list_installed_plugins 查看已安装、set_plugin_enabled 启停、remove_plugin 删除。" +
        "内置插件 ponytail 提供「拒绝过度设计」的工程约束，impeccable 提供「界面打磨」的设计约束，均默认启用。";

    private static readonly SkillStore Skills = new();

    /// <summary>稳定前缀：仅依赖用户长期设置，任务之间字节不变。</summary>
    public static string StablePrefix(AppConfig cfg) =>
        string.IsNullOrWhiteSpace(cfg.SystemPrompt) ? DefaultSystemPrompt : cfg.SystemPrompt;

    /// <summary>运行时上下文：技能与能力提示。变化时应追加而非改写前缀。</summary>
    public static string RuntimeContext()
    {
        var parts = new List<string>();
        var skills = Skills.Enabled();
        if (skills.Count > 0)
        {
            var sb = new System.Text.StringBuilder("【可用技能】按需遵循其中的步骤：");
            foreach (var s in skills)
            {
                sb.Append("\n\n### ").Append(s.Name);
                if (!string.IsNullOrWhiteSpace(s.Description)) sb.Append('\n').Append(s.Description);
                sb.Append('\n').Append(s.Content.Trim());
            }
            parts.Add(sb.ToString());
        }
        parts.Add(PluginHint);
        return string.Join("\n\n", parts);
    }

    /// <summary>取历史中最近一条运行时 system 消息内容；无则返回 null。</summary>
    public static string? LatestRuntime(IEnumerable<ChatMessage> history)
    {
        string? found = null;
        foreach (var m in history)
            if (m.Role == "system") found = m.Content;
        return found;
    }

    /// <summary>按缓存稳定规则拼装本次请求消息。</summary>
    public static List<ChatMessage> Assemble(AppConfig cfg, List<ChatMessage> history, string userContent)
    {
        var runtime = RuntimeContext();
        var messages = new List<ChatMessage> { new() { Role = "system", Content = StablePrefix(cfg) } };
        messages.AddRange(history);
        if (LatestRuntime(history) != runtime)
            messages.Add(new ChatMessage { Role = "system", Content = runtime });
        messages.Add(new ChatMessage { Role = "user", Content = userContent });
        return messages;
    }
}

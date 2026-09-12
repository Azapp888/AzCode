using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json.Nodes;
using System.Windows.Automation;

namespace AzCode.Desktop.Services;

/// <summary>
/// Windows 本机自动化能力：UI Automation 读屏、鼠标/键盘输入、PowerShell 执行。
/// 相当于 Android 端的无障碍服务 + Shizuku，但作用于本机桌面。
/// </summary>
public static class WindowsAutomation
{
    private const int MaxNodes = 300;
    private const int MaxDepth = 40;

    private static readonly HashSet<string> InteractiveTypes = new()
    {
        "Button", "Edit", "ComboBox", "CheckBox", "RadioButton", "Hyperlink",
        "ListItem", "MenuItem", "TabItem", "TreeItem", "SplitButton",
        "Slider", "Spinner", "DataItem",
    };

    // ==================== 读屏 ====================

    /// <summary>读取当前活动窗口的控件树（名称/类型/坐标/可用性）。</summary>
    public static string DumpScreenJson()
    {
        var hwnd = GetForegroundWindow();
        if (hwnd == IntPtr.Zero)
            return Err("没有活动窗口");

        AutomationElement root;
        try
        {
            root = AutomationElement.FromHandle(hwnd);
        }
        catch (Exception ex)
        {
            return Err($"无法读取窗口：{ex.Message}");
        }

        var nodes = new JsonArray();
        var count = 0;

        void Walk(AutomationElement el, int depth)
        {
            if (el is null || count >= MaxNodes || depth > MaxDepth) return;
            try
            {
                var rect = el.Current.BoundingRectangle;
                var name = el.Current.Name ?? "";
                var type = ShortType(el.Current.ControlType.ProgrammaticName);

                if (rect.Width > 0 && rect.Height > 0 && !el.Current.IsOffscreen &&
                    (!string.IsNullOrWhiteSpace(name) || InteractiveTypes.Contains(type)))
                {
                    nodes.Add(new JsonObject
                    {
                        ["name"] = name,
                        ["type"] = type,
                        ["x"] = (int)(rect.X + rect.Width / 2),
                        ["y"] = (int)(rect.Y + rect.Height / 2),
                        ["w"] = (int)rect.Width,
                        ["h"] = (int)rect.Height,
                        ["enabled"] = el.Current.IsEnabled,
                    });
                    count++;
                }

                var child = TreeWalker.ControlViewWalker.GetFirstChild(el);
                while (child is not null && count < MaxNodes)
                {
                    Walk(child, depth + 1);
                    child = TreeWalker.ControlViewWalker.GetNextSibling(child);
                }
            }
            catch
            {
                // 部分节点访问会抛异常，跳过即可
            }
        }

        Walk(root, 0);

        var title = "?";
        try { title = root.Current.Name ?? ""; } catch { /* ignore */ }

        return new JsonObject
        {
            ["ok"] = true,
            ["window"] = title,
            ["nodes"] = nodes,
        }.ToJsonString();
    }

    // ==================== 点击 ====================

    /// <summary>按控件名称点击（优先 Invoke/Select 模式，回退到坐标点击）。</summary>
    public static bool ClickByText(string text)
    {
        var hwnd = GetForegroundWindow();
        if (hwnd == IntPtr.Zero) return false;

        AutomationElement root;
        try { root = AutomationElement.FromHandle(hwnd); }
        catch { return false; }

        AutomationElement? hit = null;

        void Walk(AutomationElement el, int depth)
        {
            if (hit is not null || el is null || depth > MaxDepth) return;
            try
            {
                var name = el.Current.Name ?? "";
                if (name.Contains(text, StringComparison.OrdinalIgnoreCase) &&
                    InteractiveTypes.Contains(ShortType(el.Current.ControlType.ProgrammaticName)))
                {
                    hit = el;
                    return;
                }
                var child = TreeWalker.ControlViewWalker.GetFirstChild(el);
                while (child is not null && hit is null)
                {
                    Walk(child, depth + 1);
                    child = TreeWalker.ControlViewWalker.GetNextSibling(child);
                }
            }
            catch
            {
                // 忽略不可访问节点
            }
        }

        Walk(root, 0);
        if (hit is null) return false;

        if (TryInvoke(hit)) return true;

        try
        {
            var rect = hit.Current.BoundingRectangle;
            if (rect.Width <= 0 || rect.Height <= 0) return false;
            ClickAt((int)(rect.X + rect.Width / 2), (int)(rect.Y + rect.Height / 2));
            return true;
        }
        catch
        {
            return false;
        }
    }

    private static bool TryInvoke(AutomationElement el)
    {
        try
        {
            if (el.TryGetCurrentPattern(InvokePattern.Pattern, out var p) && p is InvokePattern inv)
            {
                inv.Invoke();
                return true;
            }
            if (el.TryGetCurrentPattern(SelectionItemPattern.Pattern, out var s) && s is SelectionItemPattern sel)
            {
                sel.Select();
                return true;
            }
        }
        catch
        {
            // 模式不可用，回退坐标点击
        }
        return false;
    }

    /// <summary>在屏幕坐标处左键单击。</summary>
    public static void ClickAt(int x, int y)
    {
        SetCursorPos(x, y);
        Thread.Sleep(30);
        mouse_event(MOUSEEVENTF_LEFTDOWN, x, y, 0, UIntPtr.Zero);
        Thread.Sleep(20);
        mouse_event(MOUSEEVENTF_LEFTUP, x, y, 0, UIntPtr.Zero);
    }

    // ==================== 输入 ====================

    /// <summary>向当前焦点窗口输入文本（Unicode 扫描码）。</summary>
    public static void TypeText(string text)
    {
        foreach (var ch in text)
        {
            SendUnicode(ch, keyUp: false);
            SendUnicode(ch, keyUp: true);
        }
    }

    /// <summary>按下组合键，如 "ctrl+s"、"enter"、"alt+f4"。</summary>
    public static void PressKeys(string combo)
    {
        var parts = combo.Split('+', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
        var mods = new List<ushort>();
        var main = new List<ushort>();

        foreach (var p in parts)
        {
            var vk = ResolveVk(p);
            if (vk == 0) continue;
            if (IsModifier(vk)) mods.Add(vk);
            else main.Add(vk);
        }

        foreach (var m in mods) SendVk(m, keyUp: false);
        foreach (var k in main)
        {
            SendVk(k, keyUp: false);
            SendVk(k, keyUp: true);
        }
        for (var i = mods.Count - 1; i >= 0; i--) SendVk(mods[i], keyUp: true);
    }

    // ==================== Shell ====================

    /// <summary>执行 PowerShell 命令，返回 stdout+stderr。</summary>
    public static string Shell(string command, int timeoutMs = 60_000)
    {
        var psi = new ProcessStartInfo("powershell.exe",
            "-NoProfile -NonInteractive -ExecutionPolicy Bypass -Command -")
        {
            RedirectStandardInput = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true,
            StandardOutputEncoding = Encoding.UTF8,
            StandardErrorEncoding = Encoding.UTF8,
        };

        using var p = Process.Start(psi);
        if (p is null) return Err("无法启动 PowerShell");

        p.StandardInput.Write(command);
        p.StandardInput.Close();

        var stdout = p.StandardOutput.ReadToEnd();
        var stderr = p.StandardError.ReadToEnd();
        if (!p.WaitForExit(timeoutMs))
        {
            try { p.Kill(entireProcessTree: true); } catch { /* ignore */ }
            return Err($"命令超时（{timeoutMs}ms）");
        }

        return new JsonObject
        {
            ["ok"] = p.ExitCode == 0,
            ["exit_code"] = p.ExitCode,
            ["output"] = Truncate(stdout + stderr, 8000),
        }.ToJsonString();
    }

    // ==================== helpers ====================

    private static string Err(string message) =>
        new JsonObject { ["ok"] = false, ["error"] = message }.ToJsonString();

    private static string Truncate(string s, int max) =>
        s.Length <= max ? s : s[..max] + "…";

    private static string ShortType(string programmatic) =>
        programmatic.Replace("ControlType.", "");

    private static bool IsModifier(ushort vk) =>
        vk is 0x11 or 0x10 or 0x12 or 0x5B or 0x5C; // ctrl, shift, alt, lwin, rwin

    private static ushort ResolveVk(string name)
    {
        var key = name.Trim().ToLowerInvariant();
        return key switch
        {
            "ctrl" or "control" => 0x11,
            "shift" => 0x10,
            "alt" => 0x12,
            "win" or "meta" => 0x5B,
            "enter" or "return" => 0x0D,
            "tab" => 0x09,
            "esc" or "escape" => 0x1B,
            "space" => 0x20,
            "backspace" => 0x08,
            "delete" or "del" => 0x2E,
            "up" => 0x26,
            "down" => 0x28,
            "left" => 0x25,
            "right" => 0x27,
            "home" => 0x24,
            "end" => 0x23,
            "pageup" => 0x21,
            "pagedown" => 0x22,
            "f1" => 0x70, "f2" => 0x71, "f3" => 0x72, "f4" => 0x73,
            "f5" => 0x74, "f6" => 0x75, "f7" => 0x76, "f8" => 0x77,
            "f9" => 0x78, "f10" => 0x79, "f11" => 0x7A, "f12" => 0x7B,
            _ when key.Length == 1 && char.IsLetter(key[0]) => (ushort)char.ToUpperInvariant(key[0]),
            _ when key.Length == 1 && char.IsDigit(key[0]) => (ushort)key[0],
            _ => 0,
        };
    }

    private static void SendUnicode(char ch, bool keyUp)
    {
        var input = new INPUT
        {
            type = INPUT_KEYBOARD,
            U = new InputUnion
            {
                ki = new KEYBDINPUT
                {
                    wVk = 0,
                    wScan = ch,
                    dwFlags = KEYEVENTF_UNICODE | (keyUp ? KEYEVENTF_KEYUP : 0),
                },
            },
        };
        SendInput(1, new[] { input }, Marshal.SizeOf<INPUT>());
    }

    private static void SendVk(ushort vk, bool keyUp)
    {
        var input = new INPUT
        {
            type = INPUT_KEYBOARD,
            U = new InputUnion
            {
                ki = new KEYBDINPUT
                {
                    wVk = vk,
                    wScan = 0,
                    dwFlags = keyUp ? KEYEVENTF_KEYUP : 0,
                },
            },
        };
        SendInput(1, new[] { input }, Marshal.SizeOf<INPUT>());
    }

    // ==================== P/Invoke ====================

    [DllImport("user32.dll")]
    private static extern IntPtr GetForegroundWindow();

    [DllImport("user32.dll")]
    private static extern bool SetCursorPos(int x, int y);

    [DllImport("user32.dll")]
    private static extern void mouse_event(uint dwFlags, int dx, int dy, uint dwData, UIntPtr dwExtraInfo);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);

    private const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
    private const uint MOUSEEVENTF_LEFTUP = 0x0004;
    private const int INPUT_KEYBOARD = 1;
    private const uint KEYEVENTF_KEYUP = 0x0002;
    private const uint KEYEVENTF_UNICODE = 0x0004;

    [StructLayout(LayoutKind.Sequential)]
    private struct INPUT
    {
        public int type;
        public InputUnion U;
    }

    [StructLayout(LayoutKind.Explicit)]
    private struct InputUnion
    {
        [FieldOffset(0)] public KEYBDINPUT ki;
        [FieldOffset(0)] public MOUSEINPUT mi;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct KEYBDINPUT
    {
        public ushort wVk;
        public ushort wScan;
        public uint dwFlags;
        public uint time;
        public IntPtr dwExtraInfo;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MOUSEINPUT
    {
        public int dx;
        public int dy;
        public uint mouseData;
        public uint dwFlags;
        public uint time;
        public IntPtr dwExtraInfo;
    }
}

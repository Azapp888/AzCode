# AzCode（Linux 端）

AzCode 的 Linux 端。同名应用 **AzCode**，按形态打标签：

- **magic** —— 有桌面环境时的图形界面标签。
- **Listen** —— 无桌面环境时的命令行标签。

两种形态共用同一套 Agent 核心，都运行在本机，把自然语言任务交给可配置的大模型决策，再通过 shell、文件操作、截图与键鼠直接操作这台电脑。

## 安装

一行命令安装 `magic` 与 `listen` 到 `~/.local/bin`：

```bash
curl -fsSL https://raw.githubusercontent.com/Azapp888/AzCode/260912-feat-linux-native/linux/install.sh | bash
```

自定义安装目录：

```bash
LISTEN_PREFIX=/usr/local ./install.sh
```

安装后确保 `~/.local/bin` 在 `PATH` 中：

```bash
export PATH="$HOME/.local/bin:$PATH"
```

## 使用

```bash
listen chat                 # 进入交互式对话
listen run "整理 ~/Downloads 里的图片"   # 执行一次任务
listen doctor               # 环境自检
listen config               # 查看已配置的提供商
listen config add --name DeepSeek --base-url https://api.deepseek.com/v1 --api-key sk-xxx
listen config use DeepSeek

listen plugins search 写测试        # 扫描热门开源插件
listen plugins install owner/repo  # 一键安装
listen plugins list                # 已安装插件
listen plugins on|off <名称>        # 启停
listen plugins remove <名称>        # 删除

listen github status                        # 查看 GitHub 接入状态
listen github set --token <PAT> --repo owner/repo --branch main
listen github clear                         # 断开接入（仅清本机凭据）

magic                       # 图形界面
```

配置文件位于 `~/.config/magic-listen/config.json`。API Key 也可用环境变量提供（优先级更高）：

```bash
export LISTEN_API_KEY=sk-xxx
export LISTEN_BASE_URL=https://api.deepseek.com/v1
export LISTEN_MODEL=deepseek-chat
```

## Agent 可用工具

| 工具 | 说明 |
| --- | --- |
| `run_shell` | 用 bash 执行命令，返回退出码与输出 |
| `list_dir` / `read_file` / `write_file` | 目录浏览与文件读写 |
| `active_window` | 读取活动窗口标题（需 xdotool） |
| `screenshot` | 截图保存为图片（gnome-screenshot / scrot / imagemagick） |
| `click` / `type_text` / `press_key` | 鼠标点击、文本输入、组合键 |
| `search_plugins` / `install_plugin` / `list_installed_plugins` / `set_plugin_enabled` / `remove_plugin` | 插件市场 |
| `github_status` / `github_save_config` | 查看与保存 GitHub 接入状态 |
| `github_list_repos` / `github_get_repo` / `github_list_branches` / `github_list_commits` | 浏览仓库、分支与提交 |
| `github_read_file` / `github_write_file` | 读取与提交仓库文件（写文件自动探测 sha） |
| `github_list_issues` / `github_create_issue` / `github_comment_issue` | 管理 Issue 与评论 |
| `github_list_pulls` / `github_create_pull` | 查看与创建 Pull Request |
| `github_search_repos` | 搜索公开仓库 |
| `finish` | 结束任务并总结 |

键鼠与截图能力依赖外部命令，缺失时 `listen doctor` 会明确列出；Agent 会收到「命令不存在」并改用其他手段。

## GitHub 接入

Token 属于用户自己的凭据，仅存于本机配置文件（`~/.config/magic-listen/config.json`），应用不读取任何环境变量或平台内部变量。图形界面在「GitHub 接入」一栏填写 Token / 默认仓库 / 分支后点击保存即可；保存前会调用 GitHub API 校验 Token 并缓存登录名。Token 需要 `repo` scope。

接入后 Agent 可以读取与提交仓库文件、查看提交记录、管理 Issue 与 Pull Request；插件搜索与安装也会自动复用该 Token，可安装私有仓库中的技能。工具中不包含删除仓库等不可逆操作。

## 降缓存未命中机制

把系统提示拆成两段，最大化提供商前缀缓存命中率（移植自 deepseek-harness）：

- **稳定前缀**：第 0 条 `system`，只放用户长期设置的人设，字节永久不变。
- **运行时上下文**：技能、记忆、插件提示单独成一条 `system` 消息；内容未变时不重复插入，变化时**追加到历史末尾**，绝不重写前缀。
- **工具定义**顺序固定、不随条件增删。

支持在任意位置追加 `system` 消息的模型（如 deepseek-flash）会把最新一条视为完整系统提示，旧的那条自然被取代，前缀保持不变。相关实现见 `listen_agent/prompt_cache.py`，并配有断言测试保证前缀字节稳定。

## 目录

```
linux/
  magic_listen.py            统一入口（自动选择 GUI/CLI，可 --gui/--cli）
  install.sh                 一行安装脚本
  listen_agent/
    __init__.py              版本
    config.py                多提供商配置读写（含 GitHub 接入字段）
    github_client.py         GitHub REST 客户端
    llm.py                   OpenAI 兼容 chat/completions 客户端
    tools.py                 Linux 自动化工具 + 插件市场工具
    store.py                 技能/记忆存储与插件扫描安装
    prompt_cache.py          降缓存未命中机制
    agent.py                 决策循环
    cli.py                   Listen 命令行
    gui.py                   magic 图形界面（tkinter）
```

## 前置条件

- Python 3.10+
- 图形界面需要 `python3-tk`
- 键鼠操作建议安装 `xdotool`（X11）；截图建议安装 `gnome-screenshot`、`scrot` 或 `imagemagick`
- 一个 OpenAI 兼容的模型服务与 API Key

## 许可

MIT。

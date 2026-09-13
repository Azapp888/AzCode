"""前缀缓存稳定性（移植自 deepseek-harness 的降缓存未命中设计）。

要点：
  - 第 0 条 system 只放不随任务变化的稳定前缀，字节永久不变；
  - 技能/记忆/插件提示等运行时上下文单独成一条 system 消息；
  - 内容未变时不重复插入，变化时追加到历史末尾，绝不重写前缀；
  - 工具定义顺序固定，不随条件增删，否则 schema 变化会让缓存整段失效。

支持在任意位置追加 system 消息的模型（如 deepseek-flash）会把最新一条视为
完整系统提示，旧的那条被自然取代，前缀缓存不受影响。
"""

from __future__ import annotations

from . import store

DEFAULT_SYSTEM_PROMPT = """你是 magic（命令行下叫 Listen），一个运行在 Linux 本机的自动化助手，直接操作用户的电脑。
按需调用工具：用 run_shell 执行命令、list_dir/read_file/write_file 处理文件、screenshot 截图、active_window 查看活动窗口、click/type_text/press_key 操作键鼠。
面向用户的文字要简洁、口语化，直接说明你正在做什么或最终结果，不要输出 JSON、代码块或工具参数。
任务完成或无法继续时调用 finish，并在 summary 里用一两句话总结结果。"""

PLUGIN_HINT = (
    "【插件市场】用户想扩展能力、寻找插件时，调用 search_plugins 扫描热门开源仓库获取候选，"
    "再用 install_plugin 一键安装；用 list_installed_plugins 查看已安装、set_plugin_enabled 启停、remove_plugin 删除。"
    "内置插件 ponytail 提供「拒绝过度设计」的工程约束，默认启用。"
)


def stable_prefix(system_prompt: str | None) -> str:
    """稳定前缀：仅依赖用户长期设置，任务之间字节不变。"""
    text = (system_prompt or "").strip()
    return text or DEFAULT_SYSTEM_PROMPT


def runtime_context() -> str:
    """运行时上下文：技能、记忆与能力提示。变化时应追加而非改写前缀。"""
    parts: list[str] = []

    skills = store.enabled_skills()
    if skills:
        sb = ["【可用技能】按需遵循其中的步骤："]
        for s in skills:
            block = f"### {s.name}"
            if s.description:
                block += f"\n{s.description}"
            block += f"\n{s.content.strip()}"
            sb.append(block)
        parts.append("\n\n".join(sb))

    memory = store.enabled_memory()
    if memory:
        sb = ["【用户记忆】请在相关任务中遵循或直接使用："]
        for m in memory:
            line = f"- {m.title}：{m.content}" if m.title else f"- {m.content}"
            sb.append(line)
        parts.append("\n".join(sb))

    parts.append(PLUGIN_HINT)
    return "\n\n".join(parts)


def latest_runtime(history: list[dict]) -> str | None:
    """取历史中最近一条运行时 system 消息内容；无则返回 None。"""
    for msg in reversed(history):
        if msg.get("role") == "system":
            return msg.get("content")
    return None


def assemble(system_prompt: str | None, history: list[dict], user_content) -> list[dict]:
    """按缓存稳定规则拼装本次请求消息。"""
    runtime = runtime_context()
    messages: list[dict] = [{"role": "system", "content": stable_prefix(system_prompt)}]
    messages.extend(history)
    if latest_runtime(history) != runtime:
        messages.append({"role": "system", "content": runtime})
    messages.append({"role": "user", "content": user_content})
    return messages

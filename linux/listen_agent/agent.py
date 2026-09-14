"""Agent 决策循环：拼装缓存稳定消息 → 请求模型 → 执行工具 → 回灌结果。"""

from __future__ import annotations

import json
from typing import Callable

from . import llm, prompt_cache, tools
from .config import Config

# on_event(kind, payload)
#   thinking / assistant(text) / tool_start(name,args) / tool_result(name,ok,output) /
#   notice(text) / failure(text)
OnEvent = Callable[..., None]

MAX_HISTORY = 60


class AgentError(RuntimeError):
    pass


class Agent:
    def __init__(self, cfg: Config, on_event: OnEvent | None = None):
        self.cfg = cfg
        self.on_event = on_event or (lambda *a, **k: None)
        self.history: list[dict] = []
        self._cancelled = False

    def cancel(self) -> None:
        self._cancelled = True

    def _emit(self, kind: str, *payload) -> None:
        if self.on_event:
            self.on_event(kind, *payload)

    def run(self, task: str, attachments: list[str] | None = None) -> str:
        self._cancelled = False
        tools.configure(self.cfg)
        provider = self.cfg.active()
        if provider is None:
            raise AgentError("尚未配置模型提供商：请编辑 ~/.config/magic-listen/config.json 或运行 `listen config`")

        base, key, model = Config.resolve_credentials(provider)
        user_content: object = task
        if attachments:
            user_content = [{"type": "text", "text": task}]
            for url in attachments:
                user_content.append({"type": "image_url", "image_url": {"url": url}})

        messages = prompt_cache.assemble(self.cfg.system_prompt, self.history, user_content)
        max_steps = self.cfg.max_steps
        summary = ""

        try:
            step = 0
            while max_steps <= 0 or step < max_steps:
                if self._cancelled:
                    self._emit("notice", "已停止")
                    break
                step += 1
                self._emit("thinking")

                reply = llm.chat(
                    base, key, model, messages,
                    tools=tools.TOOL_DEFINITIONS,
                    temperature=self.cfg.temperature,
                )

                assistant: dict = {"role": "assistant", "content": reply.content or None}
                if reply.tool_calls:
                    assistant["tool_calls"] = [
                        {"id": c.id, "type": "function",
                         "function": {"name": c.name, "arguments": c.arguments}}
                        for c in reply.tool_calls
                    ]
                messages.append(assistant)

                if reply.content.strip():
                    self._emit("assistant", reply.content.strip())

                if not reply.tool_calls:
                    break

                for c in reply.tool_calls:
                    if self._cancelled:
                        self._emit("notice", "已停止")
                        break
                    try:
                        args = json.loads(c.arguments or "{}")
                        if not isinstance(args, dict):
                            args = {}
                    except json.JSONDecodeError:
                        args = {}
                    self._emit("tool_start", c.name, c.arguments)
                    result = tools.dispatch(c.name, args)
                    ok = bool(json.loads(result).get("ok"))
                    self._emit("tool_result", c.name, ok, result)
                    messages.append({"role": "tool", "tool_call_id": c.id, "content": result})
                    if c.name == "finish":
                        summary = args.get("summary", "")
                        if summary:
                            self._emit("assistant", summary)
                        self.history = self._trim(messages[1:])
                        return summary
                else:
                    continue
                break

            if max_steps > 0 and step >= max_steps:
                self._emit("notice", f"已达到最大步数 {max_steps}，任务停止")
        finally:
            self.history = self._trim(messages[1:])
        return summary

    @staticmethod
    def _trim(history: list[dict]) -> list[dict]:
        """仅在过长时裁剪，且从 user 边界切入，保持 tool 调用与结果配对完整。"""
        if len(history) <= MAX_HISTORY:
            return history
        for i in range(len(history) - MAX_HISTORY, len(history)):
            if history[i].get("role") in ("user", "system"):
                return history[i:]
        return history[-MAX_HISTORY:]

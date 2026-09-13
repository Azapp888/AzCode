"""OpenAI 兼容的 chat/completions 客户端，仅用标准库，支持 function calling。

为兼容 Anthropic / Gemini 的 OpenAI 兼容端点，统一走 /chat/completions。
"""

from __future__ import annotations

import json
import urllib.error
import urllib.request
from dataclasses import dataclass


class LlmError(RuntimeError):
    pass


@dataclass
class ToolCall:
    id: str
    name: str
    arguments: str


@dataclass
class Reply:
    content: str
    tool_calls: list[ToolCall]


def chat(base_url: str, api_key: str, model: str, messages: list[dict],
         tools: list[dict] | None = None, temperature: float = 0.2,
         timeout: int = 180) -> Reply:
    if not api_key:
        raise LlmError("未配置 API Key：请在配置文件中填写，或设置 LISTEN_API_KEY")

    body: dict = {
        "model": model,
        "messages": messages,
        "temperature": temperature,
    }
    if tools:
        body["tools"] = tools
        body["tool_choice"] = "auto"

    req = urllib.request.Request(
        f"{base_url}/chat/completions",
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {api_key}",
            "User-Agent": "magic-listen",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "ignore")
        raise LlmError(f"HTTP {e.code}: {detail[:400]}") from e
    except Exception as e:  # noqa: BLE001
        raise LlmError(f"请求失败：{e}") from e

    try:
        message = payload["choices"][0]["message"]
    except (KeyError, IndexError) as e:
        raise LlmError(f"响应缺少 choices：{str(payload)[:300]}") from e

    calls: list[ToolCall] = []
    for c in message.get("tool_calls") or []:
        fn = c.get("function") or {}
        calls.append(ToolCall(
            id=c.get("id", ""),
            name=fn.get("name", ""),
            arguments=fn.get("arguments") or "{}",
        ))
    return Reply(content=message.get("content") or "", tool_calls=calls)

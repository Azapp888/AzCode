"""Linux 本机自动化工具，以及插件市场工具。

命令执行、文件读写直接用标准库；键鼠与截图在可用时调用 xdotool / scrot 等外部命令，
缺失时返回可读的错误，便于模型改用其他手段。
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
from pathlib import Path

from . import store

MAX_OUTPUT = 8000
DEFAULT_TIMEOUT = 60


def _ok(**kw) -> str:
    return json.dumps({"ok": True, **kw}, ensure_ascii=False)


def _err(message: str) -> str:
    return json.dumps({"ok": False, "error": message}, ensure_ascii=False)


def _run(cmd: list[str], timeout: int = DEFAULT_TIMEOUT, cwd: str | None = None) -> str:
    try:
        proc = subprocess.run(
            cmd, capture_output=True, text=True, timeout=timeout,
            cwd=cwd or os.path.expanduser("~"),
        )
    except subprocess.TimeoutExpired:
        return _err(f"命令超时（>{timeout}s）")
    except FileNotFoundError as e:
        return _err(f"命令不存在：{e}")
    except Exception as e:  # noqa: BLE001
        return _err(str(e))

    output = ((proc.stdout or "") + (proc.stderr or "")).strip()
    if len(output) > MAX_OUTPUT:
        output = output[:MAX_OUTPUT] + f"\n…（已截断，共 {len(output)} 字符）"
    return json.dumps({"ok": proc.returncode == 0, "exit": proc.returncode, "output": output},
                      ensure_ascii=False)


def _need(binary: str) -> bool:
    return shutil.which(binary) is not None


# --------------------------------------------------------------- agent tools

def run_shell(cmd: str) -> str:
    if not cmd.strip():
        return _err("缺少 cmd")
    return _run(["bash", "-lc", cmd])


def list_dir(path: str) -> str:
    base = Path(os.path.expanduser(path or "~"))
    if not base.exists():
        return _err(f"路径不存在：{base}")
    try:
        entries = []
        for p in sorted(base.iterdir(), key=lambda x: (x.is_file(), x.name.lower())):
            entries.append({
                "name": p.name,
                "type": "dir" if p.is_dir() else "file",
                "size": p.stat().st_size if p.is_file() else None,
            })
        return json.dumps({"ok": True, "path": str(base), "entries": entries[:500]},
                          ensure_ascii=False)
    except Exception as e:  # noqa: BLE001
        return _err(str(e))


def read_file(path: str, max_bytes: int = 100_000) -> str:
    p = Path(os.path.expanduser(path or ""))
    if not p.is_file():
        return _err(f"文件不存在：{p}")
    try:
        data = p.read_text(encoding="utf-8", errors="replace")
        if len(data.encode("utf-8")) > max_bytes:
            data = data.encode("utf-8")[:max_bytes].decode("utf-8", "ignore")
        return json.dumps({"ok": True, "path": str(p), "content": data}, ensure_ascii=False)
    except Exception as e:  # noqa: BLE001
        return _err(str(e))


def write_file(path: str, content: str) -> str:
    p = Path(os.path.expanduser(path or ""))
    try:
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content, encoding="utf-8")
        return _ok(path=str(p), bytes=len(content.encode("utf-8")))
    except Exception as e:  # noqa: BLE001
        return _err(str(e))


def active_window() -> str:
    if not _need("xdotool"):
        return _err("未安装 xdotool，无法读取活动窗口")
    name = _run(["xdotool", "getactivewindow", "getwindowname"], timeout=10)
    return name


def screenshot(path: str = "") -> str:
    out = os.path.expanduser(path) if path else str(Path.home() / "magic-screenshot.png")
    if _need("gnome-screenshot"):
        return _run(["gnome-screenshot", "-f", out], timeout=20)
    if _need("scrot"):
        return _run(["scrot", out], timeout=20)
    if _need("import"):
        return _run(["import", "-window", "root", out], timeout=20)
    return _err("未找到截图工具（gnome-screenshot/scrot/imagemagick）")


def click(x: int | None = None, y: int | None = None, text: str = "") -> str:
    if not _need("xdotool"):
        return _err("未安装 xdotool，无法操作鼠标")
    if text:
        # 先激活名称包含该文本的窗口，再聚焦，无法定位控件（X11 无统一控件树）。
        _run(["xdotool", "search", "--name", text, "windowactivate", "--sync"], timeout=10)
    if x is not None and y is not None:
        _run(["xdotool", "mousemove", str(x), str(y)], timeout=10)
        return _run(["xdotool", "click", "1"], timeout=10)
    if text:
        return _ok(note=f"已尝试激活窗口：{text}")
    return _err("需要 text 或 x/y")


def type_text(text: str) -> str:
    if not text:
        return _err("缺少 text")
    if not _need("xdotool"):
        return _err("未安装 xdotool，无法输入文本")
    return _run(["xdotool", "type", "--clearmodifiers", "--", text], timeout=20)


def press_key(keys: str) -> str:
    if not keys.strip():
        return _err("缺少 keys")
    if not _need("xdotool"):
        return _err("未安装 xdotool，无法按键")
    return _run(["xdotool", "key", "--clearmodifiers", keys], timeout=10)


# ------------------------------------------------------------- plugin tools

def search_plugins(keyword: str = "", limit: int = 8) -> str:
    plugins = store.search_plugins(keyword, limit)
    return json.dumps({
        "ok": True,
        "count": len(plugins),
        "plugins": [
            {"id": p.id, "name": p.name, "description": p.description,
             "installUrl": p.install_url, "stars": p.stars, "tags": p.tags}
            for p in plugins
        ],
    }, ensure_ascii=False)


def install_plugin(url: str) -> str:
    if not (url or "").strip():
        return _err("缺少 url")
    try:
        skill = store.install_plugin(url)
        return _ok(installed=skill.name)
    except Exception as e:  # noqa: BLE001
        return _err(str(e))


def list_installed_plugins() -> str:
    skills = store.all_skills()
    return json.dumps({
        "ok": True,
        "count": len(skills),
        "plugins": [
            {"id": s.id, "name": s.name, "description": s.description,
             "enabled": s.enabled, "source": s.source or "内置"}
            for s in skills
        ],
    }, ensure_ascii=False)


def _resolve_skill(skill_id: str, name: str):
    skills = store.all_skills()
    for s in skills:
        if skill_id and s.id == skill_id:
            return s
    for s in skills:
        if name and (s.name == name or name in s.name):
            return s
    return None


def set_plugin_enabled(skill_id: str = "", name: str = "", enabled: bool = True) -> str:
    skill = _resolve_skill(skill_id, name)
    if not skill:
        return _err("未找到该插件")
    store.set_skill_enabled(skill.id, enabled)
    return _ok(name=skill.name, enabled=enabled)


def remove_plugin(skill_id: str = "", name: str = "") -> str:
    skill = _resolve_skill(skill_id, name)
    if not skill:
        return _err("未找到该插件")
    store.remove_skill(skill.id)
    return _ok(removed=skill.name)


def finish(summary: str) -> str:
    return _ok(summary=summary)


# ------------------------------------------------------------------ registry

def dispatch(name: str, args: dict) -> str:
    handlers = {
        "run_shell": lambda a: run_shell(a.get("cmd", "")),
        "list_dir": lambda a: list_dir(a.get("path", "~")),
        "read_file": lambda a: read_file(a.get("path", ""), int(a.get("max_bytes", 100_000) or 100_000)),
        "write_file": lambda a: write_file(a.get("path", ""), a.get("content", "")),
        "active_window": lambda a: active_window(),
        "screenshot": lambda a: screenshot(a.get("path", "")),
        "click": lambda a: click(
            int(a["x"]) if a.get("x") is not None else None,
            int(a["y"]) if a.get("y") is not None else None,
            a.get("text", ""),
        ),
        "type_text": lambda a: type_text(a.get("text", "")),
        "press_key": lambda a: press_key(a.get("keys", "")),
        "search_plugins": lambda a: search_plugins(a.get("keyword", ""), int(a.get("limit", 8) or 8)),
        "install_plugin": lambda a: install_plugin(a.get("url", "")),
        "list_installed_plugins": lambda a: list_installed_plugins(),
        "set_plugin_enabled": lambda a: set_plugin_enabled(
            a.get("id", ""), a.get("name", ""), bool(a.get("enabled", True))),
        "remove_plugin": lambda a: remove_plugin(a.get("id", ""), a.get("name", "")),
        "finish": lambda a: finish(a.get("summary", "")),
    }
    handler = handlers.get(name)
    if handler is None:
        return _err(f"未知工具 {name}")
    try:
        return handler(args)
    except Exception as e:  # noqa: BLE001
        return _err(str(e))


def _str(desc: str) -> dict:
    return {"type": "string", "description": desc}


def _num(desc: str) -> dict:
    return {"type": "number", "description": desc}


def _fn(name: str, desc: str, props: dict | None = None, required: list[str] | None = None) -> dict:
    return {
        "type": "function",
        "function": {
            "name": name,
            "description": desc,
            "parameters": {
                "type": "object",
                "properties": props or {},
                "required": required or [],
            },
        },
    }


TOOL_DEFINITIONS: list[dict] = [
    _fn("run_shell", "在用户主目录下用 bash 执行命令，返回退出码与输出。"),
    _fn("list_dir", "列出目录内容（名称、类型、大小）。", {"path": _str("目录路径，默认 ~")}, []),
    _fn("read_file", "读取文本文件内容。", {"path": _str("文件路径"), "max_bytes": _num("最多读取字节数")}, ["path"]),
    _fn("write_file", "写入文本文件（会创建父目录）。", {"path": _str("文件路径"), "content": _str("文件内容")}, ["path", "content"]),
    _fn("active_window", "读取当前活动窗口标题（需 xdotool）。", {}, []),
    _fn("screenshot", "截取整个屏幕保存为图片文件。", {"path": _str("保存路径，默认 ~/magic-screenshot.png")}, []),
    _fn("click", "点击屏幕坐标，或按窗口标题激活窗口。", {"x": _num("X 坐标"), "y": _num("Y 坐标"), "text": _str("窗口标题关键词")}, []),
    _fn("type_text", "向当前焦点输入文本（需 xdotool）。", {"text": _str("要输入的文本")}, ["text"]),
    _fn("press_key", "按下组合键，如 ctrl+s、Return、Super_L。", {"keys": _str("按键组合")}, ["keys"]),
    _fn("search_plugins", "扫描热门开源仓库，查找可安装的 Agent 插件。",
        {"keyword": _str("关键词，留空返回内置精选"), "limit": _num("返回条数，默认 8")}, []),
    _fn("install_plugin", "一键安装插件：下载仓库中的 SKILL.md 并写入技能库。",
        {"url": _str("候选的 installUrl")}, ["url"]),
    _fn("list_installed_plugins", "列出本机已安装插件及其启停状态。", {}, []),
    _fn("set_plugin_enabled", "启用或停用一个已安装插件。",
        {"id": _str("插件 id"), "name": _str("插件名称"), "enabled": {"type": "boolean", "description": "是否启用"}}, []),
    _fn("remove_plugin", "删除一个已安装插件。", {"id": _str("插件 id"), "name": _str("插件名称")}, []),
    _fn("finish", "任务结束并给出总结。", {"summary": _str("结果总结")}, ["summary"]),
]

"""Linux 本机自动化工具，以及插件市场工具。

命令执行、文件读写直接用标准库；键鼠与截图在可用时调用 xdotool / scrot 等外部命令，
缺失时返回可读的错误，便于模型改用其他手段。
"""

from __future__ import annotations

import base64
import json
import os
import shutil
import subprocess
from pathlib import Path

from . import github_client, store

MAX_OUTPUT = 8000
DEFAULT_TIMEOUT = 60

# 由 Agent 在启动时注入的配置对象，供 GitHub 工具读取用户 Token 与默认仓库。
_config = None


def configure(cfg) -> None:
    global _config
    _config = cfg


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
    plugins = store.search_plugins(keyword, limit, token=_gh_token())
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
        skill = store.install_plugin(url, token=_gh_token())
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


# ------------------------------------------------------------- github tools

_NOTHING_TOKEN = "尚未接入 GitHub，请让用户在配置中填写 Personal Access Token，或调用 github_save_config 保存。"


def _gh_token() -> str:
    return (_config.github_token if _config is not None else "").strip()


def _gh_repo(repo: str = "") -> str:
    repo = (repo or "").strip()
    if repo:
        return repo
    default = (_config.github_default_repo if _config is not None else "").strip()
    return default


def _gh_branch(branch: str = "") -> str:
    branch = (branch or "").strip()
    if branch:
        return branch
    return (_config.github_default_branch if _config is not None else "").strip()


def github_status() -> str:
    if _config is None or not _gh_token():
        return json.dumps({"ok": False, "configured": False, "hint": _NOTHING_TOKEN}, ensure_ascii=False)
    try:
        user = github_client.whoami(_gh_token())
    except Exception as e:  # noqa: BLE001
        return _err(str(e))
    return json.dumps({
        "ok": True,
        "configured": True,
        "login": user.get("login", ""),
        "defaultRepo": _config.github_default_repo,
        "defaultBranch": _config.github_default_branch,
    }, ensure_ascii=False)


def github_save_config(token: str = "", default_repo: str = "", default_branch: str = "") -> str:
    if _config is None:
        return _err("配置尚未初始化")
    token = (token or "").strip()
    if not token:
        return _err("缺少 token")
    try:
        user = github_client.whoami(token)
    except Exception as e:  # noqa: BLE001
        return _err(str(e))
    _config.github_token = token
    if default_repo:
        _config.github_default_repo = default_repo.strip()
    if default_branch:
        _config.github_default_branch = default_branch.strip()
    _config.github_login = user.get("login", "")
    _config.save()
    return _ok(login=_config.github_login, defaultRepo=_config.github_default_repo,
               defaultBranch=_config.github_default_branch)


def github_list_repos(limit: int = 30) -> str:
    if not _gh_token():
        return _err(_NOTHING_TOKEN)
    try:
        arr = github_client.list_repos(_gh_token(), limit)
    except Exception as e:  # noqa: BLE001
        return _err(str(e))
    return _ok(repos=[{
        "fullName": r.get("full_name", ""),
        "private": r.get("private", False),
        "defaultBranch": r.get("default_branch", ""),
        "description": r.get("description") or "",
    } for r in arr])


def github_get_repo(repo: str = "") -> str:
    if not _gh_token():
        return _err(_NOTHING_TOKEN)
    repo = _gh_repo(repo)
    if not repo:
        return _err("请指定仓库，或先设置默认仓库")
    try:
        r = github_client.get_repo(_gh_token(), repo)
    except Exception as e:  # noqa: BLE001
        return _err(str(e))
    return _ok(fullName=r.get("full_name", ""), private=r.get("private", False),
               defaultBranch=r.get("default_branch", ""), description=r.get("description") or "",
               stars=r.get("stargazers_count", 0), openIssues=r.get("open_issues_count", 0),
               htmlUrl=r.get("html_url", ""))


def github_list_branches(repo: str = "") -> str:
    if not _gh_token():
        return _err(_NOTHING_TOKEN)
    repo = _gh_repo(repo)
    if not repo:
        return _err("请指定仓库，或先设置默认仓库")
    try:
        arr = github_client.list_branches(_gh_token(), repo)
    except Exception as e:  # noqa: BLE001
        return _err(str(e))
    return _ok(branches=[b.get("name", "") for b in arr])


def github_read_file(repo: str = "", path: str = "", ref: str = "") -> str:
    if not _gh_token():
        return _err(_NOTHING_TOKEN)
    repo = _gh_repo(repo)
    path = (path or "").strip()
    if not repo:
        return _err("请指定仓库，或先设置默认仓库")
    if not path:
        return _err("缺少 path")
    try:
        file = github_client.get_file(_gh_token(), repo, path, _gh_branch(ref))
    except Exception as e:  # noqa: BLE001
        return _err(str(e))
    encoded = (file.get("content") or "").replace("\n", "")
    try:
        decoded = base64.b64decode(encoded).decode("utf-8", "ignore")
    except Exception:  # noqa: BLE001
        decoded = ""
    return _ok(path=file.get("path", path), sha=file.get("sha", ""),
               size=file.get("size", 0), content=decoded)


def github_write_file(repo: str = "", path: str = "", content: str = "",
                      message: str = "", branch: str = "", sha: str = "") -> str:
    if not _gh_token():
        return _err(_NOTHING_TOKEN)
    repo = _gh_repo(repo)
    path = (path or "").strip()
    if not repo:
        return _err("请指定仓库，或先设置默认仓库")
    if not path:
        return _err("缺少 path")
    branch = _gh_branch(branch)
    sha = (sha or "").strip()
    if not sha:
        try:
            sha = github_client.get_file(_gh_token(), repo, path, branch).get("sha", "")
        except Exception:  # noqa: BLE001
            sha = ""
    try:
        res = github_client.put_file(_gh_token(), repo, path, content, message, branch, sha)
    except Exception as e:  # noqa: BLE001
        return _err(str(e))
    commit = res.get("commit") or {}
    return _ok(path=path, updated=bool(sha), commit=commit.get("sha", ""),
               htmlUrl=commit.get("html_url", ""))


def github_list_commits(repo: str = "", ref: str = "", limit: int = 20) -> str:
    if not _gh_token():
        return _err(_NOTHING_TOKEN)
    repo = _gh_repo(repo)
    if not repo:
        return _err("请指定仓库，或先设置默认仓库")
    try:
        arr = github_client.list_commits(_gh_token(), repo, _gh_branch(ref), limit)
    except Exception as e:  # noqa: BLE001
        return _err(str(e))
    return _ok(commits=[{
        "sha": (c.get("sha") or "")[:8],
        "message": ((c.get("commit") or {}).get("message") or "").split("\n")[0],
        "author": ((c.get("commit") or {}).get("author") or {}).get("name", ""),
        "date": ((c.get("commit") or {}).get("author") or {}).get("date", ""),
    } for c in arr])


def github_list_issues(repo: str = "", state: str = "open", limit: int = 20) -> str:
    if not _gh_token():
        return _err(_NOTHING_TOKEN)
    repo = _gh_repo(repo)
    if not repo:
        return _err("请指定仓库，或先设置默认仓库")
    try:
        arr = github_client.list_issues(_gh_token(), repo, state or "open", limit)
    except Exception as e:  # noqa: BLE001
        return _err(str(e))
    return _ok(issues=[{
        "number": it.get("number", 0),
        "title": it.get("title", ""),
        "state": it.get("state", ""),
        "isPull": it.get("pull_request") is not None,
        "user": (it.get("user") or {}).get("login", ""),
    } for it in arr])


def github_create_issue(repo: str = "", title: str = "", body: str = "") -> str:
    if not _gh_token():
        return _err(_NOTHING_TOKEN)
    repo = _gh_repo(repo)
    title = (title or "").strip()
    if not repo:
        return _err("请指定仓库，或先设置默认仓库")
    if not title:
        return _err("缺少 title")
    try:
        res = github_client.create_issue(_gh_token(), repo, title, body)
    except Exception as e:  # noqa: BLE001
        return _err(str(e))
    return _ok(number=res.get("number", 0), htmlUrl=res.get("html_url", ""))


def github_comment_issue(repo: str = "", number: int = 0, body: str = "") -> str:
    if not _gh_token():
        return _err(_NOTHING_TOKEN)
    repo = _gh_repo(repo)
    if not repo:
        return _err("请指定仓库，或先设置默认仓库")
    if not number:
        return _err("缺少 number")
    if not (body or "").strip():
        return _err("缺少 body")
    try:
        res = github_client.comment_issue(_gh_token(), repo, int(number), body)
    except Exception as e:  # noqa: BLE001
        return _err(str(e))
    return _ok(htmlUrl=res.get("html_url", ""))


def github_list_pulls(repo: str = "", state: str = "open", limit: int = 20) -> str:
    if not _gh_token():
        return _err(_NOTHING_TOKEN)
    repo = _gh_repo(repo)
    if not repo:
        return _err("请指定仓库，或先设置默认仓库")
    try:
        arr = github_client.list_pulls(_gh_token(), repo, state or "open", limit)
    except Exception as e:  # noqa: BLE001
        return _err(str(e))
    return _ok(pulls=[{
        "number": p.get("number", 0),
        "title": p.get("title", ""),
        "state": p.get("state", ""),
        "head": (p.get("head") or {}).get("ref", ""),
        "base": (p.get("base") or {}).get("ref", ""),
    } for p in arr])


def github_create_pull(repo: str = "", title: str = "", head: str = "",
                       base: str = "", body: str = "") -> str:
    if not _gh_token():
        return _err(_NOTHING_TOKEN)
    repo = _gh_repo(repo)
    title, head, base = (title or "").strip(), (head or "").strip(), (base or "").strip()
    if not repo:
        return _err("请指定仓库，或先设置默认仓库")
    if not (title and head and base):
        return _err("缺少 title/head/base")
    try:
        res = github_client.create_pull(_gh_token(), repo, title, head, base, body)
    except Exception as e:  # noqa: BLE001
        return _err(str(e))
    return _ok(number=res.get("number", 0), htmlUrl=res.get("html_url", ""))


def github_search_repos(query: str = "", limit: int = 10) -> str:
    query = (query or "").strip()
    if not query:
        return _err("缺少 query")
    try:
        arr = github_client.search_repos(_gh_token(), query, limit)
    except Exception as e:  # noqa: BLE001
        return _err(str(e))
    return _ok(repos=[{
        "fullName": r.get("full_name", ""),
        "description": r.get("description") or "",
        "stars": r.get("stargazers_count", 0),
        "htmlUrl": r.get("html_url", ""),
    } for r in arr])


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
        "github_status": lambda a: github_status(),
        "github_save_config": lambda a: github_save_config(
            a.get("token", ""), a.get("defaultRepo", ""), a.get("defaultBranch", "")),
        "github_list_repos": lambda a: github_list_repos(int(a.get("limit", 30) or 30)),
        "github_get_repo": lambda a: github_get_repo(a.get("repo", "")),
        "github_list_branches": lambda a: github_list_branches(a.get("repo", "")),
        "github_read_file": lambda a: github_read_file(
            a.get("repo", ""), a.get("path", ""), a.get("ref", "")),
        "github_write_file": lambda a: github_write_file(
            a.get("repo", ""), a.get("path", ""), a.get("content", ""),
            a.get("message", ""), a.get("branch", ""), a.get("sha", "")),
        "github_list_commits": lambda a: github_list_commits(
            a.get("repo", ""), a.get("ref", ""), int(a.get("limit", 20) or 20)),
        "github_list_issues": lambda a: github_list_issues(
            a.get("repo", ""), a.get("state", "open"), int(a.get("limit", 20) or 20)),
        "github_create_issue": lambda a: github_create_issue(
            a.get("repo", ""), a.get("title", ""), a.get("body", "")),
        "github_comment_issue": lambda a: github_comment_issue(
            a.get("repo", ""), int(a.get("number", 0) or 0), a.get("body", "")),
        "github_list_pulls": lambda a: github_list_pulls(
            a.get("repo", ""), a.get("state", "open"), int(a.get("limit", 20) or 20)),
        "github_create_pull": lambda a: github_create_pull(
            a.get("repo", ""), a.get("title", ""), a.get("head", ""),
            a.get("base", ""), a.get("body", "")),
        "github_search_repos": lambda a: github_search_repos(
            a.get("query", ""), int(a.get("limit", 10) or 10)),
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
    _fn("github_status", "查看 GitHub 接入状态：是否已配置 Token、当前登录账号、默认仓库与分支。操作仓库前先调用。", {}, []),
    _fn("github_save_config", "保存 GitHub 接入配置（Token 与可选默认仓库/分支），保存前会校验 Token。",
        {"token": _str("GitHub Personal Access Token（需 repo 权限）"),
         "defaultRepo": _str("默认仓库 owner/repo，可省略"),
         "defaultBranch": _str("默认分支，留空用仓库默认分支")}, ["token"]),
    _fn("github_list_repos", "列出当前 Token 可访问的仓库（含私有）。",
        {"limit": _num("最多返回条数，默认 30")}, []),
    _fn("github_get_repo", "查看仓库概览：默认分支、是否私有、star、开放 Issue 数等。",
        {"repo": _str("仓库 owner/repo，省略用默认仓库")}, []),
    _fn("github_list_branches", "列出仓库分支。",
        {"repo": _str("仓库 owner/repo，省略用默认仓库")}, []),
    _fn("github_read_file", "读取仓库文件内容与 sha（更新文件时需要）。",
        {"repo": _str("仓库 owner/repo，省略用默认仓库"), "path": _str("文件路径"),
         "ref": _str("分支或 commit，省略用默认分支")}, ["path"]),
    _fn("github_write_file", "创建或更新仓库文件并产生一次提交。更新已有文件可先读取文件，或由工具自动探测 sha。",
        {"repo": _str("仓库 owner/repo，省略用默认仓库"), "path": _str("文件路径"),
         "content": _str("文件完整内容"), "message": _str("提交信息"),
         "branch": _str("提交到的分支，省略用默认分支"),
         "sha": _str("更新已有文件时的 sha；新建留空")}, ["path", "content"]),
    _fn("github_list_commits", "列出仓库提交记录。",
        {"repo": _str("仓库 owner/repo，省略用默认仓库"), "ref": _str("分支或 commit，省略用默认分支"),
         "limit": _num("最多返回条数，默认 20")}, []),
    _fn("github_list_issues", "列出仓库 Issue。",
        {"repo": _str("仓库 owner/repo，省略用默认仓库"), "state": _str("open / closed / all，默认 open"),
         "limit": _num("最多返回条数，默认 20")}, []),
    _fn("github_create_issue", "新建 Issue。",
        {"repo": _str("仓库 owner/repo，省略用默认仓库"), "title": _str("Issue 标题"),
         "body": _str("Issue 正文")}, ["title"]),
    _fn("github_comment_issue", "给 Issue 或 PR 添加评论。",
        {"repo": _str("仓库 owner/repo，省略用默认仓库"), "number": _num("Issue/PR 编号"),
         "body": _str("评论内容")}, ["number", "body"]),
    _fn("github_list_pulls", "列出仓库 Pull Request。",
        {"repo": _str("仓库 owner/repo，省略用默认仓库"), "state": _str("open / closed / all，默认 open"),
         "limit": _num("最多返回条数，默认 20")}, []),
    _fn("github_create_pull", "基于已有分支创建 Pull Request。",
        {"repo": _str("仓库 owner/repo，省略用默认仓库"), "title": _str("PR 标题"),
         "head": _str("来源分支"), "base": _str("目标分支"), "body": _str("PR 描述")},
        ["title", "head", "base"]),
    _fn("github_search_repos", "在 GitHub 搜索公开仓库（按 star 排序）。",
        {"query": _str("搜索关键词"), "limit": _num("最多返回条数，默认 10")}, ["query"]),
    _fn("finish", "任务结束并给出总结。", {"summary": _str("结果总结")}, ["summary"]),
]

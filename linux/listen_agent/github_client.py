"""GitHub REST 客户端（Contents / Repos / Issues / Pulls / Search）。

只做用户显式请求的仓库读写：读取文件、提交改动、管理 Issue 与 Pull Request。
不包含删除仓库等不可逆操作。Token 属于用户自己的凭据，仅存于本地配置
（~/.config/magic-listen/config.json），不读取任何环境变量或平台内部变量。
所有请求都带超时并在失败时抛出可读错误。
"""

from __future__ import annotations

import base64
import json
import urllib.error
import urllib.parse
import urllib.request

API = "https://api.github.com"
_UA = "magic-listen"
_API_VERSION = "2022-11-28"


class GitHubError(RuntimeError):
    pass


def _describe(code: int, body: str) -> str:
    message = ""
    try:
        message = (json.loads(body) or {}).get("message", "")
    except Exception:
        message = ""
    if code == 401:
        return "GitHub 未授权（401）：Token 无效或已过期，请在配置中重新填写。"
    if code == 403:
        return f"GitHub 拒绝访问（403）：{message or 'Token 权限不足或触发频率限制，请确认已勾选 repo scope。'}"
    if code == 404:
        return f"未找到（404）：仓库、分支或文件不存在，或 Token 无该私有仓库权限。{message}"
    if code == 409:
        return f"冲突（409）：文件已被他人修改，请重新读取后再提交。{message}"
    if code == 422:
        return f"请求无效（422）：{message}"
    return f"GitHub 请求失败（{code}）：{message}"


def _request(
    token: str,
    method: str,
    path: str,
    body: dict | None = None,
    accept: str = "application/vnd.github+json",
) -> str:
    url = path if path.startswith("http") else API + path
    data = json.dumps(body).encode("utf-8") if body is not None else None
    headers = {
        "Accept": accept,
        "User-Agent": _UA,
        "X-GitHub-Api-Version": _API_VERSION,
    }
    if token and token.strip():
        headers["Authorization"] = f"Bearer {token.strip()}"
    if data is not None:
        headers["Content-Type"] = "application/json; charset=utf-8"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.read().decode("utf-8", "ignore")
    except urllib.error.HTTPError as e:
        text = e.read().decode("utf-8", "ignore") if e.fp else ""
        raise GitHubError(_describe(e.code, text)) from e
    except urllib.error.URLError as e:
        raise GitHubError(f"网络请求失败：{e.reason}") from e


def whoami(token: str) -> dict:
    return json.loads(_request(token, "GET", "/user"))


def list_repos(token: str, limit: int = 30) -> list[dict]:
    out: list[dict] = []
    per_page = max(1, min(limit, 100))
    page = 1
    while len(out) < per_page and page <= 5:
        arr = json.loads(_request(
            token, "GET", f"/user/repos?per_page={per_page}&page={page}&sort=updated"))
        if not arr:
            break
        out.extend(arr)
        if len(arr) < per_page:
            break
        page += 1
    return out


def get_repo(token: str, repo: str) -> dict:
    return json.loads(_request(token, "GET", f"/repos/{repo}"))


def list_branches(token: str, repo: str) -> list[dict]:
    return json.loads(_request(token, "GET", f"/repos/{repo}/branches?per_page=100"))


def list_commits(token: str, repo: str, ref: str = "", limit: int = 20) -> list[dict]:
    q = f"/repos/{repo}/commits?per_page={max(1, min(limit, 100))}"
    if ref:
        q += f"&sha={_enc(ref)}"
    return json.loads(_request(token, "GET", q))


def get_file(token: str, repo: str, path: str, ref: str = "") -> dict:
    q = f"/repos/{repo}/contents/{_enc_path(path)}"
    if ref:
        q += f"?ref={_enc(ref)}"
    return json.loads(_request(token, "GET", q))


def put_file(
    token: str,
    repo: str,
    path: str,
    content: str,
    message: str,
    branch: str = "",
    sha: str = "",
) -> dict:
    body = {
        "message": message or f"Update {path}",
        "content": base64.b64encode(content.encode("utf-8")).decode("ascii"),
    }
    if branch:
        body["branch"] = branch
    if sha:
        body["sha"] = sha
    return json.loads(_request(token, "PUT", f"/repos/{repo}/contents/{_enc_path(path)}", body))


def list_issues(token: str, repo: str, state: str = "open", limit: int = 20) -> list[dict]:
    q = f"/repos/{repo}/issues?state={_enc(state)}&per_page={max(1, min(limit, 100))}"
    return json.loads(_request(token, "GET", q))


def create_issue(token: str, repo: str, title: str, body: str = "") -> dict:
    payload: dict = {"title": title}
    if body:
        payload["body"] = body
    return json.loads(_request(token, "POST", f"/repos/{repo}/issues", payload))


def comment_issue(token: str, repo: str, number: int, body: str) -> dict:
    return json.loads(_request(
        token, "POST", f"/repos/{repo}/issues/{number}/comments", {"body": body}))


def list_pulls(token: str, repo: str, state: str = "open", limit: int = 20) -> list[dict]:
    q = f"/repos/{repo}/pulls?state={_enc(state)}&per_page={max(1, min(limit, 100))}"
    return json.loads(_request(token, "GET", q))


def create_pull(
    token: str,
    repo: str,
    title: str,
    head: str,
    base: str,
    body: str = "",
) -> dict:
    payload: dict = {"title": title, "head": head, "base": base}
    if body:
        payload["body"] = body
    return json.loads(_request(token, "POST", f"/repos/{repo}/pulls", payload))


def search_repos(token: str, query: str, limit: int = 10) -> list[dict]:
    q = _enc(query.strip())
    res = json.loads(_request(
        token, "GET",
        f"/search/repositories?q={q}&sort=stars&order=desc&per_page={max(1, min(limit, 30))}"))
    return res.get("items", [])


def _enc(s: str) -> str:
    return urllib.parse.quote(s, safe="")


def _enc_path(path: str) -> str:
    """逐段编码路径，保留斜杠。"""
    parts = [p for p in path.strip("/").split("/") if p]
    return "/".join(_enc(p) for p in parts)

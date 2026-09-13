"""本地技能与记忆存储，以及热门插件扫描/安装。

技能：~/.config/magic-listen/skills.json
记忆：~/.config/magic-listen/memory.json
插件市场：内置精选 + GitHub 实时搜索，一键安装仓库中的 SKILL.md。
"""

from __future__ import annotations

import json
import re
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass, field, asdict
from pathlib import Path

from .config import CONFIG_DIR

SKILLS_PATH = CONFIG_DIR / "skills.json"
MEMORY_PATH = CONFIG_DIR / "memory.json"

PONYTAIL_ID = "builtin-ponytail"

PONYTAIL_CONTENT = """# ponytail · 拒绝过度设计

你是一个克制的工程师。每次动手前，先确认「解决当前问题所需的最小改动」，然后只做这些。

原则：
1. 只实现用户要求的功能，不擅自增加配置项、开关、抽象层或「以后可能用到」的能力。
2. 优先使用已有代码与标准库；新增依赖必须说明为什么现有手段无法完成。
3. 修改范围尽可能小，一次只解决一个问题，不顺手重构无关代码。
4. 能在一个文件解决就不用两个；能不引入接口/工厂/基类就直接写具体实现。
5. 遇到不确定的需求，先向用户提问，不要凭猜测扩大实现。
6. 完成后用一两句话说明改动，并注明是否引入了新的依赖或文件。

判断口诀：如果这段代码今天没有明确用途，就不要写。
"""


@dataclass
class Skill:
    id: str
    name: str
    description: str = ""
    content: str = ""
    source: str = ""
    enabled: bool = True


@dataclass
class Memory:
    id: str
    category: str
    title: str
    content: str
    enabled: bool = True


# ---------------------------------------------------------------- SkillStore

def _read_list(path: Path) -> list[dict]:
    try:
        if path.exists():
            return json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        pass
    return []


def _write_list(path: Path, items: list[dict]) -> None:
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(items, ensure_ascii=False, indent=2), encoding="utf-8")


def all_skills() -> list[Skill]:
    return [Skill(**s) for s in _read_list(SKILLS_PATH)]


def save_skill(skill: Skill) -> None:
    items = _read_list(SKILLS_PATH)
    for i, s in enumerate(items):
        if s.get("id") == skill.id:
            items[i] = asdict(skill)
            break
    else:
        items.append(asdict(skill))
    _write_list(SKILLS_PATH, items)


def remove_skill(skill_id: str) -> None:
    _write_list(SKILLS_PATH, [s for s in _read_list(SKILLS_PATH) if s.get("id") != skill_id])


def set_skill_enabled(skill_id: str, enabled: bool) -> None:
    items = _read_list(SKILLS_PATH)
    for s in items:
        if s.get("id") == skill_id:
            s["enabled"] = enabled
    _write_list(SKILLS_PATH, items)


def enabled_skills() -> list[Skill]:
    return [s for s in all_skills() if s.enabled]


def seed_builtins() -> None:
    if any(s.id == PONYTAIL_ID for s in all_skills()):
        return
    save_skill(Skill(
        id=PONYTAIL_ID,
        name="ponytail · 拒绝过度设计",
        description="只做最小必要改动，避免多余依赖、抽象与投机功能。",
        content=PONYTAIL_CONTENT.strip(),
        source="",
    ))


# --------------------------------------------------------------- MemoryStore

def all_memory() -> list[Memory]:
    return [Memory(**m) for m in _read_list(MEMORY_PATH)]


def enabled_memory() -> list[Memory]:
    return [m for m in all_memory() if m.enabled]


def add_memory(category: str, title: str, content: str) -> Memory:
    m = Memory(id=uuid.uuid4().hex, category=category, title=title, content=content)
    items = _read_list(MEMORY_PATH)
    items.append(asdict(m))
    _write_list(MEMORY_PATH, items)
    return m


# ----------------------------------------------------------- PluginCatalog

@dataclass
class Plugin:
    id: str
    name: str
    description: str
    install_url: str
    stars: int = 0
    tags: list[str] = field(default_factory=list)


CURATED = [
    Plugin(
        id="ponytail",
        name="ponytail · 拒绝过度设计",
        description="让 Agent 只做最小必要改动：不引入多余依赖、不预先抽象、不写投机功能。",
        install_url="ilindaniel/ponytail-lite",
        tags=["工程", "简洁", "反过度设计"],
    ),
]

_GITHUB_SEARCH = "https://api.github.com/search/repositories"


def search_plugins(keyword: str, limit: int = 8) -> list[Plugin]:
    kw = (keyword or "").strip()
    result: list[Plugin] = []
    seen: set[str] = set()

    def add(p: Plugin) -> None:
        if p.install_url.lower() in seen:
            return
        seen.add(p.install_url.lower())
        result.append(p)

    if not kw:
        return list(CURATED)

    lc = kw.lower()
    for p in CURATED:
        if lc in p.name.lower() or lc in p.description.lower() or any(lc in t.lower() for t in p.tags):
            add(p)

    try:
        q = urllib.parse.quote(f"{kw} SKILL.md in:name,description,readme")
        url = f"{_GITHUB_SEARCH}?q={q}&sort=stars&order=desc&per_page={max(1, min(limit, 20))}"
        req = urllib.request.Request(url, headers={
            "Accept": "application/vnd.github+json",
            "User-Agent": "magic-listen",
        })
        with urllib.request.urlopen(req, timeout=15) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
        for item in payload.get("items", []):
            full = item.get("full_name", "")
            if full:
                add(Plugin(
                    id="gh-" + full.replace("/", "-"),
                    name=full,
                    description=(item.get("description") or "")[:160],
                    install_url=full,
                    stars=item.get("stargazers_count", 0),
                ))
    except Exception:
        pass

    return result[: limit + len(CURATED)]


def _resolve_raw_url(value: str) -> str:
    value = value.strip()
    if not value:
        raise ValueError("请输入 GitHub 链接")
    if value.startswith("http://") or value.startswith("https://"):
        url = value
    elif re.match(r"^[\w.-]+/[\w.-]+(/.*)?$", value):
        url = f"https://github.com/{value}"
    else:
        raise ValueError("无法识别的链接格式")

    raw_base = "https://raw.githubusercontent.com/"
    if url.startswith(raw_base):
        return url

    m = re.match(r"^https?://github\.com/([^/]+)/([^/]+)(.*)$", url)
    if not m:
        raise ValueError("只支持 github.com 或 raw.githubusercontent.com 链接")
    owner, repo, rest = m.group(1), m.group(2).removesuffix(".git"), m.group(3).lstrip("/")

    blob = re.match(r"^blob/([^/]+)/(.+)$", rest)
    if blob:
        return f"{raw_base}{owner}/{repo}/{blob.group(1)}/{blob.group(2)}"
    tree = re.match(r"^tree/([^/]+)(?:/(.*))?$", rest)
    if tree:
        sub = (tree.group(2) or "").strip("/")
        path = "SKILL.md" if not sub else f"{sub}/SKILL.md"
        return f"{raw_base}{owner}/{repo}/{tree.group(1)}/{path}"
    raw = re.match(r"^raw/([^/]+)/(.+)$", rest)
    if raw:
        return f"{raw_base}{owner}/{repo}/{raw.group(1)}/{raw.group(2)}"
    if not rest:
        return f"{raw_base}{owner}/{repo}/HEAD/SKILL.md"
    raise ValueError("请指向 SKILL.md 文件或仓库目录")


def _download(url: str, max_bytes: int = 512 * 1024) -> str:
    req = urllib.request.Request(url, headers={
        "Accept": "text/plain, text/markdown, */*",
        "User-Agent": "magic-listen",
    })
    with urllib.request.urlopen(req, timeout=25) as resp:
        data = resp.read(max_bytes + 1)
    if len(data) > max_bytes:
        raise ValueError("技能文件过大（超过 512 KB）")
    text = data.decode("utf-8", "ignore")
    if not text.strip():
        raise ValueError("技能文件内容为空")
    return text


def _parse_skill(raw: str) -> tuple[str, str, str]:
    text = raw.replace("\r\n", "\n")
    name, description, body = "", "", text
    fm = re.match(r"^---\n([\s\S]*?)\n---\n?([\s\S]*)$", text)
    if fm:
        front, body = fm.group(1), fm.group(2)
        m = re.search(r"(?m)^name:\s*(.+)$", front)
        if m:
            name = m.group(1).strip().strip("\"'")
        m = re.search(r"(?m)^description:\s*(.+)$", front)
        if m:
            description = m.group(1).strip().strip("\"'")
    if not name:
        m = re.search(r"(?m)^#\s+(.+)$", body)
        if m:
            name = m.group(1).strip()
    if not name:
        name = "未命名技能"
    if not description:
        line = next((ln.strip() for ln in body.splitlines()
                     if ln.strip() and not ln.strip().startswith("#")), "")
        description = line[:120]
    content = body.strip()
    if not content:
        raise ValueError("技能正文为空")
    return name, description, content


def install_plugin(install_url: str) -> Skill:
    raw_url = _resolve_raw_url(install_url)
    body = _download(raw_url)
    name, description, content = _parse_skill(body)
    skill = Skill(
        id="gh-" + uuid.uuid5(uuid.NAMESPACE_URL, raw_url).hex[:12],
        name=name,
        description=description,
        content=content,
        source=raw_url,
    )
    save_skill(skill)
    return skill

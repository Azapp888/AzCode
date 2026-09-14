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

IMPECCABLE_ID = "builtin-impeccable"

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

# 移植自 impeccable（Apache-2.0，https://github.com/pbakaus/impeccable），
# 按 AzCode 桌面原生（Tk）场景提炼为可直接注入系统提示词的版本。
IMPECCABLE_CONTENT = """# impeccable · 界面打磨

你是资深设计总监，目标是把界面做到「值得被点名」的完成度：生产级实现、明确观点、厚待用户、讲究的细节。面向桌面原生界面（Tk）。

## 先证据，后动手
1. 先取真实参考再改代码：拿到用户指定的参考产品/截图后，提取其真实色值、圆角、间距、字级；禁止凭记忆猜色值。
2. 分清「精修」与「重做」：精修保留既有识别、行为与文案；重做只保留产品事实与功能，把旧外观当反面参照。
3. 方向未定前不改 UI；方向确认后一口气做完。

## 必须达标（对着成品核验）
- 对比度：正文与占位文字至少 4.5:1，大号文字至少 3:1；彩色底上的次要文字用同色系加深，不要用灰。
- 层次：阴影必须有偏移加柔和模糊；零偏移的彩色光晕只是装饰。
- 间距：同组紧凑、组间宽松；标题上方的留白大于下方。
- 字体：标题与正文有明确的字号与字重台阶，字号随系统设置缩放。
- 状态：按压、悬停、禁用、加载、错误、空态齐备；控件真的可用。
- 文案：控件名说清动作，错误信息说清问题与恢复方式。

## 明确拒绝（白给时不要用）
- 用「同尺寸卡片 + 图标 + 标题 + 文字」当页面骨架；卡片嵌套一律错。
- 标题上方的 kicker/eyebrow 小标签。
- 01/02/03 章节编号，除非顺序本身携带信息。
- 渐变文字、装饰性玻璃模糊、超过 1dp 的彩色左边框、硬偏移阴影。
- 用 emoji 或 Unicode 字符冒充图标；图标应来自统一描边粗细的图标体系。
- 按品类而不是使用场景决定明暗主题。

## 交付纪律
- 不做开放式反复自检：构建完整、批量检查一次、一轮修完、至多再确认一轮即停。
- 报告说明改了哪些文件、是否新增依赖或文件。
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
    seeded = {s.id for s in all_skills()}
    if PONYTAIL_ID not in seeded:
        save_skill(Skill(
            id=PONYTAIL_ID,
            name="ponytail · 拒绝过度设计",
            description="只做最小必要改动，避免多余依赖、抽象与投机功能。",
            content=PONYTAIL_CONTENT.strip(),
            source="",
        ))
    if IMPECCABLE_ID not in seeded:
        save_skill(Skill(
            id=IMPECCABLE_ID,
            name="impeccable · 界面打磨",
            description="先取真实参考，再按工艺底线把界面做到生产级完成度。",
            content=IMPECCABLE_CONTENT.strip(),
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
    Plugin(
        id="impeccable",
        name="impeccable · 界面打磨",
        description="把界面做到生产级完成度：先取真实参考，再按工艺底线逐项核验，拒绝廉价设计套路。",
        install_url="pbakaus/impeccable",
        tags=["设计", "界面", "打磨"],
    ),
]

_GITHUB_SEARCH = "https://api.github.com/search/repositories"


def search_plugins(keyword: str, limit: int = 8, token: str = "") -> list[Plugin]:
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
        headers = {
            "Accept": "application/vnd.github+json",
            "User-Agent": "magic-listen",
        }
        if (token or "").strip():
            headers["Authorization"] = f"Bearer {token.strip()}"
        req = urllib.request.Request(url, headers=headers)
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


def _download(url: str, max_bytes: int = 512 * 1024, token: str = "") -> str:
    headers = {
        "Accept": "text/plain, text/markdown, */*",
        "User-Agent": "magic-listen",
    }
    if (token or "").strip():
        headers["Authorization"] = f"Bearer {token.strip()}"
    req = urllib.request.Request(url, headers=headers)
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


def install_plugin(install_url: str, token: str = "") -> Skill:
    raw_url = _resolve_raw_url(install_url)
    body = _download(raw_url, token=token)
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

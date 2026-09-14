"""配置读写：多提供商账号，存于 ~/.config/magic-listen/config.json。

API Key 由用户自行填写，也可用环境变量 LISTEN_API_KEY / LISTEN_BASE_URL / LISTEN_MODEL
覆盖当前账号（不读取任何平台内部变量）。
"""

from __future__ import annotations

import json
import os
import uuid
from dataclasses import dataclass, field, asdict
from pathlib import Path

CONFIG_DIR = Path(os.environ.get("LISTEN_CONFIG_DIR", Path.home() / ".config" / "magic-listen"))
CONFIG_PATH = CONFIG_DIR / "config.json"

DEFAULT_BASE = "https://api.deepseek.com/v1"
DEFAULT_MODEL = "deepseek-chat"


@dataclass
class Provider:
    id: str
    name: str
    base_url: str = DEFAULT_BASE
    api_key: str = ""
    model: str = DEFAULT_MODEL
    models: list[str] = field(default_factory=list)
    enabled: bool = True

    @staticmethod
    def new(name: str, base_url: str = DEFAULT_BASE, api_key: str = "",
            model: str = DEFAULT_MODEL, models: list[str] | None = None) -> "Provider":
        return Provider(
            id=uuid.uuid4().hex[:12],
            name=name,
            base_url=base_url,
            api_key=api_key,
            model=model,
            models=models or ([model] if model else []),
            enabled=True,
        )


@dataclass
class Config:
    providers: list[Provider] = field(default_factory=list)
    active_id: str = ""
    max_steps: int = 0  # 0 表示无限
    temperature: float = 0.2
    system_prompt: str = ""

    # GitHub 接入：用户自己的 Personal Access Token 与可选默认仓库/分支。
    github_token: str = ""
    github_default_repo: str = ""
    github_default_branch: str = ""
    github_login: str = ""

    # ---------- 持久化 ----------

    @staticmethod
    def load() -> "Config":
        try:
            if CONFIG_PATH.exists():
                raw = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
                providers = [Provider(**p) for p in raw.get("providers", [])]
                return Config(
                    providers=providers,
                    active_id=raw.get("active_id", ""),
                    max_steps=raw.get("max_steps", 0),
                    temperature=raw.get("temperature", 0.2),
                    system_prompt=raw.get("system_prompt", ""),
                    github_token=raw.get("github_token", ""),
                    github_default_repo=raw.get("github_default_repo", ""),
                    github_default_branch=raw.get("github_default_branch", ""),
                    github_login=raw.get("github_login", ""),
                )
        except Exception:
            pass
        return Config()

    def save(self) -> None:
        CONFIG_DIR.mkdir(parents=True, exist_ok=True)
        data = {
            "providers": [asdict(p) for p in self.providers],
            "active_id": self.active_id,
            "max_steps": self.max_steps,
            "temperature": self.temperature,
            "system_prompt": self.system_prompt,
            "github_token": self.github_token,
            "github_default_repo": self.github_default_repo,
            "github_default_branch": self.github_default_branch,
            "github_login": self.github_login,
        }
        CONFIG_PATH.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")

    @property
    def github_configured(self) -> bool:
        return bool(self.github_token.strip())

    # ---------- 账号 ----------

    def active(self) -> Provider | None:
        for p in self.providers:
            if p.id == self.active_id and p.enabled:
                return p
        for p in self.providers:
            if p.enabled:
                return p
        return self.providers[0] if self.providers else None

    def upsert(self, provider: Provider) -> None:
        for i, p in enumerate(self.providers):
            if p.id == provider.id:
                self.providers[i] = provider
                break
        else:
            self.providers.append(provider)

    def set_active(self, provider_id: str) -> None:
        self.active_id = provider_id

    # ---------- 生效凭据（环境变量优先） ----------

    @staticmethod
    def resolve_credentials(p: Provider) -> tuple[str, str, str]:
        base = os.environ.get("LISTEN_BASE_URL") or p.base_url or DEFAULT_BASE
        key = os.environ.get("LISTEN_API_KEY") or p.api_key
        model = os.environ.get("LISTEN_MODEL") or p.model or DEFAULT_MODEL
        return base.rstrip("/"), key, model

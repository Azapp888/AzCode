"""Listen —— 命令行界面（无桌面环境下使用，与 magic GUI 共用 Agent 核心）。"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import sys
from pathlib import Path

from . import __version__, github_client, store
from .agent import Agent, AgentError
from .config import CONFIG_PATH, Config, Provider

BANNER = f"Listen {__version__} —— 输入任务回车执行；/help 查看命令，/exit 退出。"


def _print_event(kind: str, *payload) -> None:
    if kind == "thinking":
        print("· 思考中…", flush=True)
    elif kind == "assistant":
        print(f"\n{payload[0]}\n")
    elif kind == "tool_start":
        name, args = payload[0], payload[1]
        print(f"  → {name} {args[:200]}", flush=True)
    elif kind == "tool_result":
        name, ok, output = payload
        flag = "ok" if ok else "fail"
        print(f"  ← {name} [{flag}] {output[:300]}", flush=True)
    elif kind in ("notice", "failure"):
        print(f"  ! {payload[0]}")


def _run_task(cfg: Config, task: str, session: Agent | None) -> Agent:
    agent = session or Agent(cfg, on_event=_print_event)
    if session is None:
        agent.on_event = _print_event
    try:
        agent.run(task)
    except AgentError as e:
        print(f"错误：{e}", file=sys.stderr)
    except KeyboardInterrupt:
        agent.cancel()
        print("\n已中断。", file=sys.stderr)
    return agent


# ------------------------------------------------------------------ commands

def cmd_run(args) -> int:
    cfg = Config.load()
    _run_task(cfg, args.task, None)
    return 0


def cmd_chat(args) -> int:
    cfg = Config.load()
    agent = Agent(cfg, on_event=_print_event)
    print(BANNER)
    _repl(cfg, agent)
    return 0


def _repl(cfg: Config, agent: Agent) -> None:
    while True:
        try:
            raw = input("listen> ").strip()
        except (EOFError, KeyboardInterrupt):
            print()
            return
        if not raw:
            continue
        if raw in ("/exit", "/quit", "exit", "quit"):
            return
        if raw == "/help":
            print("命令：/exit 退出，/clear 清空上下文，/config 查看配置，"
                  "/plugins 查看插件，/doctor 环境自检")
            continue
        if raw == "/clear":
            agent.history.clear()
            print("已清空上下文。")
            continue
        if raw == "/config":
            _show_config(cfg)
            continue
        if raw == "/plugins":
            _show_plugins()
            continue
        if raw == "/doctor":
            cmd_doctor(None)
            continue
        try:
            agent.run(raw)
        except AgentError as e:
            print(f"错误：{e}", file=sys.stderr)
        except KeyboardInterrupt:
            agent.cancel()
            print("\n已中断。")


def _show_config(cfg: Config) -> None:
    active = cfg.active()
    print(f"配置文件：{CONFIG_PATH}")
    if not cfg.providers:
        print("（尚未配置任何提供商）")
    for p in cfg.providers:
        mark = "*" if active and p.id == active.id else " "
        key = "已设置" if p.api_key or os.environ.get("LISTEN_API_KEY") else "未设置"
        print(f" {mark} {p.name}  {p.base_url}  模型={p.model}  Key={key}")
    if cfg.github_configured:
        repo = cfg.github_default_repo or "（未设默认仓库）"
        print(f" GitHub：已接入 {cfg.github_login or ''}  默认仓库={repo}")
    else:
        print(" GitHub：未接入（listen github set --token <PAT>）")


def _show_plugins() -> None:
    skills = store.all_skills()
    if not skills:
        print("（尚未安装插件）")
        return
    for s in skills:
        state = "on " if s.enabled else "off"
        print(f" [{state}] {s.name}  {s.description or ''}")


def cmd_config(args) -> int:
    cfg = Config.load()
    if args.action == "list" or args.action is None:
        _show_config(cfg)
        return 0
    if args.action == "add":
        if not args.name or not args.base_url:
            print("需要 --name 与 --base-url", file=sys.stderr)
            return 2
        provider = Provider.new(
            name=args.name, base_url=args.base_url,
            api_key=args.api_key or "", model=args.model or "deepseek-chat",
        )
        cfg.upsert(provider)
        if not cfg.active_id:
            cfg.set_active(provider.id)
        cfg.save()
        print(f"已添加：{provider.name}（id={provider.id}）")
        return 0
    if args.action == "use":
        target = next((p for p in cfg.providers if p.name == args.name or p.id == args.name), None)
        if not target:
            print(f"未找到提供商：{args.name}", file=sys.stderr)
            return 1
        cfg.set_active(target.id)
        cfg.save()
        print(f"当前使用：{target.name}")
        return 0
    print(f"未知操作：{args.action}", file=sys.stderr)
    return 2


def cmd_plugins(args) -> int:
    cfg = Config.load()
    token = cfg.github_token
    if args.action == "list" or args.action is None:
        _show_plugins()
        return 0
    if args.action == "search":
        for p in store.search_plugins(args.keyword or "", token=token):
            stars = f" ★{p.stars}" if p.stars else ""
            print(f" {p.name}{stars}\n   {p.description}\n   {p.install_url}")
        return 0
    if args.action == "install":
        if not args.url:
            print("需要仓库地址或 installUrl", file=sys.stderr)
            return 2
        try:
            skill = store.install_plugin(args.url, token=token)
            print(f"已安装：{skill.name}")
            return 0
        except Exception as e:  # noqa: BLE001
            print(f"安装失败：{e}", file=sys.stderr)
            return 1
    if args.action in ("on", "off"):
        skill = _find_skill(args.name or "")
        if not skill:
            print("未找到该插件", file=sys.stderr)
            return 1
        store.set_skill_enabled(skill.id, args.action == "on")
        print(f"{skill.name} 已{'启用' if args.action == 'on' else '停用'}")
        return 0
    if args.action == "remove":
        skill = _find_skill(args.name or "")
        if not skill:
            print("未找到该插件", file=sys.stderr)
            return 1
        store.remove_skill(skill.id)
        print(f"已删除：{skill.name}")
        return 0
    print(f"未知操作：{args.action}", file=sys.stderr)
    return 2


def _find_skill(name: str):
    for s in store.all_skills():
        if s.name == name or name in s.name or s.id == name:
            return s
    return None


def cmd_doctor(_args) -> int:
    print(f"Listen {__version__}")
    print(f"Python : {sys.version.split()[0]}")
    print(f"配置   : {CONFIG_PATH}")
    print(f"桌面   : {'是' if _has_desktop() else '否'}")
    for binary in ("xdotool", "gnome-screenshot", "scrot", "import", "bash"):
        mark = shutil.which(binary) is not None
        print(f"  {binary:<16} {'可用' if mark else '缺失'}")
    cfg = Config.load()
    print(f"提供商 : {len(cfg.providers)} 个，当前 {cfg.active().name if cfg.active() else '无'}")
    return 0


def _has_desktop() -> bool:
    return bool(os.environ.get("DISPLAY") or os.environ.get("WAYLAND_DISPLAY"))


def cmd_github(args) -> int:
    cfg = Config.load()
    action = args.action or "status"
    if action == "status":
        if not cfg.github_configured:
            print("未接入 GitHub。用 `listen github set --token <PAT>` 接入。")
            return 0
        try:
            user = github_client.whoami(cfg.github_token)
        except Exception as e:  # noqa: BLE001
            print(f"Token 校验失败：{e}", file=sys.stderr)
            return 1
        print(f"已接入：{user.get('login', '')}")
        print(f"默认仓库：{cfg.github_default_repo or '（未设置）'}")
        print(f"默认分支：{cfg.github_default_branch or '（仓库默认分支）'}")
        return 0
    if action == "set":
        token = (args.token or "").strip()
        if not token:
            print("需要 --token", file=sys.stderr)
            return 2
        try:
            user = github_client.whoami(token)
        except Exception as e:  # noqa: BLE001
            print(f"Token 校验失败：{e}", file=sys.stderr)
            return 1
        cfg.github_token = token
        if args.repo:
            cfg.github_default_repo = args.repo.strip()
        if args.branch:
            cfg.github_default_branch = args.branch.strip()
        cfg.github_login = user.get("login", "")
        cfg.save()
        print(f"已接入 GitHub：{cfg.github_login}")
        return 0
    if action == "clear":
        cfg.github_token = ""
        cfg.github_default_repo = ""
        cfg.github_default_branch = ""
        cfg.github_login = ""
        cfg.save()
        print("已断开 GitHub 接入。")
        return 0
    print(f"未知操作：{action}", file=sys.stderr)
    return 2


# ------------------------------------------------------------------- parser

def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="listen", description="AzCode Linux 命令行 Agent")
    parser.add_argument("--version", action="version", version=f"Listen {__version__}")
    sub = parser.add_subparsers(dest="command")

    p_run = sub.add_parser("run", help="执行一次任务")
    p_run.add_argument("task")
    p_run.set_defaults(func=cmd_run)

    p_chat = sub.add_parser("chat", help="进入交互式对话")
    p_chat.set_defaults(func=cmd_chat)

    p_cfg = sub.add_parser("config", help="查看或编辑配置")
    p_cfg.add_argument("action", nargs="?", choices=["list", "add", "use"])
    p_cfg.add_argument("--name")
    p_cfg.add_argument("--base-url")
    p_cfg.add_argument("--api-key")
    p_cfg.add_argument("--model")
    p_cfg.set_defaults(func=cmd_config)

    p_pl = sub.add_parser("plugins", help="管理插件")
    p_pl.add_argument("action", nargs="?", choices=["list", "search", "install", "on", "off", "remove"])
    p_pl.add_argument("name", nargs="?")
    p_pl.add_argument("--keyword", default="")
    p_pl.add_argument("--url", default="")
    p_pl.set_defaults(func=cmd_plugins)

    p_doc = sub.add_parser("doctor", help="环境自检")
    p_doc.set_defaults(func=cmd_doctor)

    p_gh = sub.add_parser("github", help="接入与管理用户 GitHub 仓库")
    p_gh.add_argument("action", nargs="?", choices=["status", "set", "clear"], default="status")
    p_gh.add_argument("--token", default="")
    p_gh.add_argument("--repo", default="")
    p_gh.add_argument("--branch", default="")
    p_gh.set_defaults(func=cmd_github)

    return parser


def main(argv: list[str] | None = None) -> int:
    store.seed_builtins()
    parser = build_parser()
    args = parser.parse_args(argv)
    if not getattr(args, "command", None):
        return cmd_chat(args)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())

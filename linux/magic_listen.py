#!/usr/bin/env python3
"""magic / Listen 统一入口。

有桌面环境时启动图形界面 magic；无桌面（或 --cli）时启动命令行 Listen。
强制模式：--gui / --cli，或环境变量 MAGIC_LISTEN_MODE=gui|cli。
"""

from __future__ import annotations

import os
import sys


def _has_desktop() -> bool:
    return bool(os.environ.get("DISPLAY") or os.environ.get("WAYLAND_DISPLAY"))


def _tk_available() -> bool:
    try:
        import tkinter  # noqa: F401
        return True
    except Exception:
        return False


def main() -> int:
    argv = sys.argv[1:]
    mode = os.environ.get("MAGIC_LISTEN_MODE", "").lower()
    if "--cli" in argv:
        mode = "cli"
        argv.remove("--cli")
    if "--gui" in argv:
        mode = "gui"
        argv.remove("--gui")

    if mode in ("", "auto"):
        mode = "gui" if (_has_desktop() and _tk_available()) else "cli"

    if mode == "gui":
        try:
            from listen_agent import gui
            return gui.main()
        except Exception as e:  # noqa: BLE001
            print(f"无法启动图形界面（{e}），改用命令行模式。", file=sys.stderr)

    from listen_agent import cli
    return cli.main(argv)


if __name__ == "__main__":
    raise SystemExit(main())

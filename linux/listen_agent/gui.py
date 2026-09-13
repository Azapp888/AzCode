"""magic —— 图形界面（有桌面环境时使用，与 Listen CLI 共用 Agent 核心）。"""

from __future__ import annotations

import queue
import threading
import tkinter as tk
from tkinter import ttk
from tkinter.scrolledtext import ScrolledText

from . import __version__, store
from .agent import Agent, AgentError
from .config import Config, Provider


class MagicApp:
    def __init__(self, root: tk.Tk):
        self.root = root
        self.cfg = Config.load()
        self.events: "queue.Queue[tuple]" = queue.Queue()
        self.agent: Agent | None = None
        self.worker: threading.Thread | None = None

        root.title(f"magic {__version__}")
        root.geometry("900x640")
        self._build()
        self._load_active_provider()
        root.after(80, self._drain_events)

    # ------------------------------------------------------------------ UI

    def _build(self) -> None:
        pad = {"padx": 8, "pady": 6}

        settings = ttk.LabelFrame(self.root, text="模型提供商")
        settings.pack(fill="x", **pad)

        self.var_name = tk.StringVar()
        self.var_base = tk.StringVar()
        self.var_key = tk.StringVar()
        self.var_model = tk.StringVar()

        row1 = ttk.Frame(settings)
        row1.pack(fill="x", padx=6, pady=4)
        ttk.Label(row1, text="名称").pack(side="left")
        ttk.Entry(row1, textvariable=self.var_name, width=14).pack(side="left", padx=4)
        ttk.Label(row1, text="Base URL").pack(side="left")
        ttk.Entry(row1, textvariable=self.var_base, width=34).pack(side="left", padx=4)

        row2 = ttk.Frame(settings)
        row2.pack(fill="x", padx=6, pady=4)
        ttk.Label(row2, text="API Key").pack(side="left")
        ttk.Entry(row2, textvariable=self.var_key, width=34, show="•").pack(side="left", padx=4)
        ttk.Label(row2, text="模型").pack(side="left")
        ttk.Entry(row2, textvariable=self.var_model, width=20).pack(side="left", padx=4)
        ttk.Button(row2, text="保存配置", command=self._save_provider).pack(side="left", padx=6)

        self.log = ScrolledText(self.root, wrap="word", state="disabled", font=("TkFixedFont", 10))
        self.log.pack(fill="both", expand=True, **pad)

        composer = ttk.Frame(self.root)
        composer.pack(fill="x", **pad)
        self.var_task = tk.StringVar()
        entry = ttk.Entry(composer, textvariable=self.var_task)
        entry.pack(side="left", fill="x", expand=True)
        entry.bind("<Return>", lambda _e: self._run())
        self.btn_run = ttk.Button(composer, text="运行任务", command=self._run)
        self.btn_run.pack(side="left", padx=6)
        self.btn_stop = ttk.Button(composer, text="停止", command=self._stop, state="disabled")
        self.btn_stop.pack(side="left")

        self._append(f"magic {__version__} 已就绪。填写模型信息后输入任务即可。\n"
                     f"内置插件 ponytail（拒绝过度设计）已启用。\n")

    def _load_active_provider(self) -> None:
        p = self.cfg.active()
        if p:
            self.var_name.set(p.name)
            self.var_base.set(p.base_url)
            self.var_key.set(p.api_key)
            self.var_model.set(p.model)

    def _save_provider(self) -> None:
        name = self.var_name.get().strip() or "DeepSeek"
        base = self.var_base.get().strip() or "https://api.deepseek.com/v1"
        model = self.var_model.get().strip() or "deepseek-chat"
        key = self.var_key.get().strip()
        current = self.cfg.active()
        if current and current.name == name:
            current.base_url, current.api_key, current.model = base, key, model
            if model not in current.models:
                current.models.append(model)
            provider = current
        else:
            provider = Provider.new(name=name, base_url=base, api_key=key, model=model)
            self.cfg.upsert(provider)
            self.cfg.set_active(provider.id)
        self.cfg.save()
        self._append(f"已保存提供商：{provider.name}\n")

    # --------------------------------------------------------------- events

    def _run(self) -> None:
        task = self.var_task.get().strip()
        if not task or self.worker:
            return
        self.var_task.set("")
        self.agent = Agent(self.cfg, on_event=self._on_event)
        self.btn_run.configure(state="disabled")
        self.btn_stop.configure(state="normal")
        self.worker = threading.Thread(target=self._run_worker, args=(task,), daemon=True)
        self.worker.start()

    def _run_worker(self, task: str) -> None:
        try:
            self.agent.run(task)
        except AgentError as e:
            self._on_event("failure", str(e))
        except Exception as e:  # noqa: BLE001
            self._on_event("failure", f"运行异常：{e}")
        finally:
            self._on_event("__done__")

    def _stop(self) -> None:
        if self.agent:
            self.agent.cancel()

    def _on_event(self, kind: str, *payload) -> None:
        self.events.put((kind, payload))

    def _drain_events(self) -> None:
        try:
            while True:
                kind, payload = self.events.get_nowait()
                if kind == "__done__":
                    self.worker = None
                    self.btn_run.configure(state="normal")
                    self.btn_stop.configure(state="disabled")
                    continue
                if kind == "thinking":
                    self._append("· 思考中…\n")
                elif kind == "assistant":
                    self._append(f"\n{payload[0]}\n")
                elif kind == "tool_start":
                    self._append(f"  → {payload[0]} {payload[1][:200]}\n")
                elif kind == "tool_result":
                    name, ok, output = payload
                    self._append(f"  ← {name} [{'ok' if ok else 'fail'}] {output[:300]}\n")
                elif kind in ("notice", "failure"):
                    self._append(f"  ! {payload[0]}\n")
        except queue.Empty:
            pass
        self.root.after(80, self._drain_events)

    def _append(self, text: str) -> None:
        self.log.configure(state="normal")
        self.log.insert("end", text)
        self.log.see("end")
        self.log.configure(state="disabled")


def main() -> int:
    store.seed_builtins()
    root = tk.Tk()
    MagicApp(root)
    root.mainloop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

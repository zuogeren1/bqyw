# -*- coding: utf-8 -*-
"""百趣演武 · 自动抽牌 —— Tkinter 图形界面。

    python gui.py            普通启动
    pythonw gui.py           启动时不带控制台窗口

要真正操控游戏必须有管理员权限（UIPI 会静默丢弃低权限进程的点击），
界面顶部会显示当前权限，未提权时点「开始」会弹提示并提供一键重启。
"""
from __future__ import annotations

import ctypes
import os
import queue
import sys
import threading
import traceback
import tkinter as tk
from tkinter import messagebox, scrolledtext, ttk

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from bqyw.screen import _enable_dpi_awareness  # noqa: E402  （必须在建窗口前）

_enable_dpi_awareness()

from bqyw import __version__, applog, paths  # noqa: E402
from bqyw.bot import Abort, Bot  # noqa: E402
from bqyw.hotkey import parse  # noqa: E402
from bqyw.privilege import is_admin, relaunch_as_admin  # noqa: E402
from bqyw.rules import (HAND_NAME_CN, POINT_ANY, SUITS, SUIT_UNKNOWN, Card,  # noqa: E402
                        Hand)
from bqyw.targets import (ANY, CustomTarget, HandTarget, MultiTarget,  # noqa: E402
                          estimate_attempts)

TARGETS = ["四条", "同花", "葫芦", "顺子", "三条", "两对", "对子", "同花顺", "自定义"]
MULTI = "多选"
# 多选面板里的展示顺序：由易到难，自定义垫底
PICK_ORDER = ["对子", "两对", "三条", "顺子", "葫芦", "同花", "四条", "同花顺", "自定义"]
MAX_ROWS = 5
NAME_TO_HAND = {v: k for k, v in HAND_NAME_CN.items()}
NAME_TO_HAND["散牌"] = Hand.HIGH

APP_TITLE = f"百趣演武 · 自动抽牌  v{__version__}"


class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title(APP_TITLE)
        self.geometry("740x640")
        self.minsize(700, 560)

        self.q: queue.Queue = queue.Queue()
        self.stop_event = threading.Event()
        self.worker: threading.Thread | None = None
        self.running = False
        self.log_file = None
        self.log_path = None

        self.v_target = tk.StringVar(value="四条")
        self.multi_sel: list[str] = []        # 「多选」勾中的目标名
        self.v_lucky = tk.StringVar(value="对子")
        self.v_attempts = tk.StringVar(value="0")
        self.v_maxbp = tk.StringVar(value="0")
        self.v_dry = tk.BooleanVar(value=False)
        self.v_fast = tk.BooleanVar(value=False)
        self.v_minimize = tk.BooleanVar(value=True)
        self.v_hotkey = tk.StringVar(value="f11")
        self.custom_targets: list[CustomTarget] = [
            CustomTarget(tuple(Card(p, SUIT_UNKNOWN) for p in (1, 2, 3, 4, 5)), ordered=False)]
        self._est_cache: dict = {}
        self.v_status = tk.StringVar(value="就绪")
        self.v_state = tk.StringVar(value="轮次 —   张数 —   战力点 —   牌型 —")
        self.v_tally = tk.StringVar(value="第 0 轮   最好 —   最高 0")

        self._build()
        self._refresh_privilege()
        self.protocol("WM_DELETE_WINDOW", self._on_close)
        self.after(120, self._pump)

    # ------------------------------------------------------------ 界面
    def _build(self):
        pad = {"padx": 8, "pady": 4}

        top = ttk.LabelFrame(self, text="参数")
        top.pack(fill="x", **pad)

        row1 = ttk.Frame(top)
        row1.pack(fill="x", padx=8, pady=(6, 2))
        ttk.Label(row1, text="目标牌型").pack(side="left")
        cb = ttk.Combobox(row1, textvariable=self.v_target,
                          values=TARGETS + [MULTI], width=8, state="readonly")
        cb.pack(side="left", padx=(6, 8))
        cb.bind("<<ComboboxSelected>>", lambda _e: self._sync_estimate())
        ttk.Button(row1, text="自定义…", width=7, command=self._edit_custom).pack(
            side="left", padx=(0, 6))
        ttk.Button(row1, text="多选…", width=6, command=self._pick_targets).pack(
            side="left", padx=(0, 12))

        ttk.Label(row1, text="今日好运").pack(side="left")
        ttk.Combobox(row1, textvariable=self.v_lucky, values=["无"] + TARGETS,
                     width=8, state="readonly").pack(side="left", padx=(6, 12))

        ttk.Label(row1, text="重开上限").pack(side="left")
        ttk.Entry(row1, textvariable=self.v_attempts, width=6).pack(side="left", padx=(6, 0))

        row2 = ttk.Frame(top)
        row2.pack(fill="x", padx=8, pady=(2, 8))
        ttk.Label(row2, text="战力点上限").pack(side="left")
        ttk.Entry(row2, textvariable=self.v_maxbp, width=6).pack(side="left", padx=(6, 4))
        ttk.Label(row2, text="0=不限", foreground="#888").pack(side="left", padx=(0, 14))
        ttk.Checkbutton(row2, text="只读", variable=self.v_dry).pack(side="left")
        cb_fast = ttk.Checkbutton(row2, text="快速模式", variable=self.v_fast,
                                  command=self._sync_estimate)
        cb_fast.pack(side="left", padx=(12, 0))
        ttk.Checkbutton(row2, text="运行后最小化",
                        variable=self.v_minimize).pack(side="left", padx=12)

        row3 = ttk.Frame(top)
        row3.pack(fill="x", padx=8, pady=(0, 8))
        self.lbl_est = ttk.Label(row3, text="", foreground="#555")
        self.lbl_est.pack(side="left")
        ttk.Entry(row3, textvariable=self.v_hotkey, width=9).pack(side="right")
        ttk.Label(row3, text="急停键").pack(side="right", padx=(0, 4))

        # 权限条
        perm = ttk.Frame(self)
        perm.pack(fill="x", padx=16, pady=(0, 4))
        self.lbl_perm = ttk.Label(perm, text="权限检查中…")
        self.lbl_perm.pack(side="left")
        self.btn_elev = ttk.Button(perm, text="以管理员重启", command=self._elevate)

        btns = ttk.Frame(self)
        btns.pack(fill="x", padx=16, pady=6)
        self.btn_start = ttk.Button(btns, text="开始", command=self._start)
        self.btn_start.pack(side="left")
        self.btn_stop = ttk.Button(btns, text="停止", command=self._stop, state="disabled")
        self.btn_stop.pack(side="left", padx=8)
        ttk.Button(btns, text="读一帧", command=self._once).pack(side="left", padx=8)
        ttk.Label(btns, textvariable=self.v_status, foreground="#0a7").pack(side="right")

        stat = ttk.LabelFrame(self, text="实时状态")
        stat.pack(fill="x", padx=8, pady=4)
        ttk.Label(stat, textvariable=self.v_state, font=("Consolas", 11)).pack(
            anchor="w", padx=10, pady=(6, 0))
        ttk.Label(stat, textvariable=self.v_tally, font=("Consolas", 11)).pack(
            anchor="w", padx=10, pady=(0, 8))

        logs = ttk.LabelFrame(self, text="日志")
        logs.pack(fill="both", expand=True, padx=8, pady=(4, 8))
        self.txt = scrolledtext.ScrolledText(logs, height=12, state="disabled",
                                             wrap="word", font=("Consolas", 9))
        self.txt.pack(fill="both", expand=True, padx=6, pady=6)

        self._sync_estimate()

    # ------------------------------------------------------------ 杂项
    def _append(self, line: str):
        self.txt.configure(state="normal")
        self.txt.insert("end", line + "\n")
        self.txt.see("end")
        self.txt.configure(state="disabled")
        if self.log_file is not None:                  # 同步落盘，出问题能翻日志
            try:
                self.log_file.write(line + "\n")
            except OSError:
                self.log_file = None

    # ------------------------------------------------------------ 日志文件
    def _open_log(self, *head_lines: str):
        """开一份新日志（旧的超量会被删掉），下面显示的每一行都会同步进去。"""
        self._close_log()
        head = "\n".join([applog.env_header(), *head_lines])
        try:
            self.log_path, self.log_file = applog.new_log(header=head)
        except OSError as exc:
            self.log_path, self.log_file = None, None
            self._append(f"{applog.stamp()} [!] 日志文件打不开：{exc}")
            return
        self._append(f"{applog.stamp()} ── 日志：{self.log_path}")

    def _close_log(self):
        if self.log_file is None:
            return
        try:
            self.log_file.flush()
            self.log_file.close()
        except OSError:
            pass
        self.log_file = None

    def _pick_targets(self):
        """勾选可接受的目标；两个以上就走多选。"""
        dlg = tk.Toplevel(self)
        dlg.title("可接受的目标")
        dlg.transient(self)
        dlg.grab_set()
        dlg.resizable(False, False)

        ttk.Label(dlg, text="勾几个都行，达成其中任意一个就停手。",
                  foreground="#333").pack(anchor="w", padx=12, pady=(10, 2))
        ttk.Label(dlg,
                  text="程序按倍率从高到低追求：先搏最想要的那个，"
                       "它没戏了才退而求其次。",
                  foreground="#777").pack(anchor="w", padx=12, pady=(0, 8))

        grid = ttk.Frame(dlg)
        grid.pack(padx=12, pady=(0, 8))
        cur = set(self.multi_sel) if self.v_target.get() == MULTI else {self.v_target.get()}
        vars_ = {}
        for i, name in enumerate(PICK_ORDER):
            v = tk.BooleanVar(value=name in cur)
            vars_[name] = v
            ttk.Checkbutton(grid, text=name, variable=v).grid(
                row=i // 3, column=i % 3, sticky="w", padx=(0, 22), pady=3)

        btns = ttk.Frame(dlg)
        btns.pack(fill="x", padx=12, pady=(0, 12))

        def ok():
            picked = [n for n in PICK_ORDER if vars_[n].get()]
            if not picked:
                messagebox.showinfo(APP_TITLE, "至少选一个目标。", parent=dlg)
                return
            if len(picked) == 1:
                self.multi_sel = []
                self.v_target.set(picked[0])
            else:
                self.multi_sel = picked
                self.v_target.set(MULTI)
            dlg.destroy()
            self._sync_estimate()

        ttk.Button(btns, text="取消", command=dlg.destroy).pack(side="right")
        ttk.Button(btns, text="确定", command=ok).pack(side="right", padx=6)

    def _targets_for(self, kind: str) -> list:
        """一个名字可能对应多个目标 —— 自定义可以写好几种。"""
        if kind == "自定义":
            return list(self.custom_targets) or [
                CustomTarget(tuple(Card(p, SUIT_UNKNOWN) for p in (1, 2, 3, 4, 5)))]
        return [HandTarget(NAME_TO_HAND.get(kind, Hand.QUADS))]

    def _current_target(self):
        """按界面当前选择造出目标对象；多个就合成一个多目标。"""
        kinds = self.multi_sel if self.v_target.get() == MULTI else [self.v_target.get()]
        pool = []
        for k in kinds:
            pool.extend(self._targets_for(k))
        if not pool:
            return HandTarget(Hand.QUADS)
        return pool[0] if len(pool) == 1 else MultiTarget(pool)

    def _edit_custom(self):
        """自定义目标：可以写好几组，达成任意一组就停手。"""
        dlg = tk.Toplevel(self)
        dlg.title("自定义目标")
        dlg.transient(self)
        dlg.grab_set()
        dlg.resizable(False, False)

        ttk.Label(dlg, text="每一组都是「想要的一手牌」；写几组，达成任意一组就停手，不再往下抽。",
                  foreground="#333").pack(anchor="w", padx=12, pady=(10, 2))
        ttk.Label(dlg, text="点数 / 花色选「任意」表示不挑；第 2 张起可以选「不抽」。",
                  foreground="#777").pack(anchor="w", padx=12, pady=(0, 8))

        # 版面数据：[组][行] = (点数, 花色)，行数固定 MAX_ROWS
        groups: list[list[tuple[str, str]]] = []
        for t in self.custom_targets:
            g: list[tuple[str, str]] = []
            for i in range(MAX_ROWS):
                if i < len(t.cards):
                    c = t.cards[i]
                    g.append((ANY if c.point == POINT_ANY else str(c.point),
                              ANY if c.suit == SUIT_UNKNOWN else c.suit))
                else:
                    g.append(("不抽", ANY))
            groups.append(g)
        if not groups:
            groups = [[("1", ANY), ("不抽", ANY), ("不抽", ANY), ("不抽", ANY), ("不抽", ANY)]]

        body = ttk.Frame(dlg)
        body.pack(fill="x", padx=12)
        rows_of: list[list[list]] = []          # 每组每行的 [vp, vs, pc, sc]

        def sync_back():
            for gi, rows in enumerate(rows_of):
                for i, (vp, vs, _pc, _sc) in enumerate(rows):
                    groups[gi][i] = (vp.get(), vs.get())

        def grey_out(rows):
            """第一个「不抽」之后的行全部置灰。"""
            cut = None
            for idx in range(1, len(rows)):
                if rows[idx][0].get() == "不抽":
                    cut = idx
                    break
            for idx, (_vp, _vs, pc, sc) in enumerate(rows):
                on = cut is None or idx <= cut
                pc.configure(state="readonly" if on else "disabled")
                sc.configure(state="readonly" if on else "disabled")

        def render():
            for w in body.winfo_children():
                w.destroy()
            rows_of.clear()
            for gi, g in enumerate(groups):
                box = ttk.LabelFrame(body, text=f"第 {gi + 1} 组")
                box.pack(fill="x", pady=(0, 6))
                rows = []
                for i in range(MAX_ROWS):
                    f = ttk.Frame(box)
                    f.pack(fill="x", padx=8, pady=2)
                    ttk.Label(f, text=f"第 {i + 1} 张", width=7).pack(side="left")
                    vp = tk.StringVar(value=g[i][0])
                    vs = tk.StringVar(value=g[i][1])
                    points = [ANY, "1", "2", "3", "4", "5"]
                    opts = points if i == 0 else ["不抽"] + points
                    pc = ttk.Combobox(f, textvariable=vp, values=opts, width=6,
                                      state="readonly")
                    pc.pack(side="left", padx=(0, 6))
                    sc = ttk.Combobox(f, textvariable=vs, values=[ANY] + list(SUITS),
                                      width=7, state="readonly")
                    sc.pack(side="left")
                    pc.bind("<<ComboboxSelected>>", lambda _e, r=rows: grey_out(r))
                    rows.append([vp, vs, pc, sc])
                rows_of.append(rows)
                grey_out(rows)
                if len(groups) > 1:
                    ttk.Button(box, text="删除这一组", width=10,
                               command=lambda gi=gi: drop_group(gi)).pack(
                        anchor="e", padx=8, pady=(0, 6))

        def drop_group(gi: int):
            sync_back()
            groups.pop(gi)
            render()

        def add_group():
            sync_back()
            groups.append([("1", ANY), ("不抽", ANY), ("不抽", ANY),
                           ("不抽", ANY), ("不抽", ANY)])
            render()

        render()

        bar = ttk.Frame(dlg)
        bar.pack(fill="x", padx=12, pady=(0, 4))
        ttk.Button(bar, text="+ 再加一组", command=add_group).pack(side="left")

        v_ord = tk.BooleanVar(value=self.custom_targets[0].ordered
                              if self.custom_targets else False)
        ttk.Checkbutton(dlg, text="按顺序（第 1 张必须是指定的第 1 张…，对每组都生效）",
                        variable=v_ord).pack(anchor="w", padx=12, pady=(4, 4))

        btns = ttk.Frame(dlg)
        btns.pack(fill="x", padx=12, pady=(4, 12))

        def ok():
            sync_back()
            out: list[CustomTarget] = []
            for g in groups:
                cards = []
                for i, (p, s) in enumerate(g):
                    if i and p == "不抽":
                        break
                    if p == "不抽":
                        continue
                    point = POINT_ANY if p == ANY else int(p)
                    cards.append(Card(point, SUIT_UNKNOWN if s == ANY else s))
                if not cards:
                    continue
                try:
                    t = CustomTarget(tuple(cards), ordered=v_ord.get())
                except ValueError as exc:
                    messagebox.showerror(APP_TITLE, str(exc), parent=dlg)
                    return
                if t not in out:
                    out.append(t)
            if not out:
                messagebox.showinfo(APP_TITLE, "至少要写一张牌。", parent=dlg)
                return
            self.custom_targets = out
            if self.v_target.get() != "自定义":
                self.v_target.set("自定义")
            dlg.destroy()
            self._sync_estimate()

        ttk.Button(btns, text="取消", command=dlg.destroy).pack(side="right")
        ttk.Button(btns, text="确定", command=ok).pack(side="right", padx=6)

    def _sync_estimate(self):
        """算期望重开次数。

        多选目标精确算下来要 1~3 秒，卡在界面线程里就是「点了没反应」。
        所以先显示「计算中…」，扔到后台线程算完再回填。
        """
        target = self._current_target()
        fast = bool(self.v_fast.get())
        key = (str(target), fast)
        self._est_want = key
        if key in self._est_cache:
            self._show_estimate(key, target, fast, self._est_cache[key])
            return

        self.lbl_est.configure(text=f"{target} ｜ 计算中…")

        def work():
            try:
                n = estimate_attempts(target)
            except Exception:                                  # noqa: BLE001
                n = float("inf")
            self._est_cache[key] = n
            self.q.put(("estimate", (key, target, fast, n)))

        threading.Thread(target=work, daemon=True).start()

    def _show_estimate(self, key, target, fast: bool, n: float):
        if getattr(self, "_est_want", None) != key:
            return                       # 已经切到别的目标了，这条作废
        tag = "快速 " if fast else ""
        if not n or n > 1e7:
            self.lbl_est.configure(text=f"{tag}{target} ｜ 基本抽不到")
            return
        per_round = 8.0 if fast else 8.5       # 实测：快速固定 5 张 8.0s，慢速能提前撤但每抽更慢
        mins = n * per_round / 60
        if mins >= 90:
            cost = f"{mins / 60:.1f} 小时"
        elif mins >= 1:
            cost = f"{mins:.0f} 分钟"
        else:
            cost = f"{mins * 60:.0f} 秒"
        self.lbl_est.configure(text=f"{tag}{target} ｜ 期望 {n:,.0f} 轮 ≈ {cost}")

    def _refresh_privilege(self):
        if is_admin():
            self.lbl_perm.configure(text="权限：已提权 ✓", foreground="#0a7")
            self.btn_elev.pack_forget()
        else:
            self.lbl_perm.configure(text="权限：未提权 —— 点击会被系统静默丢弃",
                                    foreground="#c00")
            self.btn_elev.pack(side="left", padx=12)

    def _elevate(self):
        if relaunch_as_admin(script=os.path.join(HERE, "gui.py")):
            self.destroy()
        else:
            messagebox.showerror(APP_TITLE, "提权失败或被取消。")

    def _busy(self, busy: bool):
        self.running = busy
        self.btn_start.configure(state="disabled" if busy else "normal")
        self.btn_stop.configure(state="normal" if busy else "disabled")
        self.v_status.set("运行中…" if busy else "就绪")

    # ------------------------------------------------------------ 运行
    def _start(self):
        if self.running:
            return
        if not self.v_dry.get() and not is_admin():
            messagebox.showwarning(
                APP_TITLE,
                "未以管理员身份运行，点击会被 Windows 静默丢弃。\n"
                "请点「以管理员重启」，或勾选「只读」先看判定。")
            return

        target = self._current_target()
        lucky_name = self.v_lucky.get()
        lucky = NAME_TO_HAND.get(lucky_name) if lucky_name and lucky_name != "无" else None
        try:
            max_attempts = max(0, int(self.v_attempts.get() or 0))
            max_bp = max(0, int(self.v_maxbp.get() or 0))
        except ValueError:
            messagebox.showerror(APP_TITLE, "重开上限 / 战力点上限要填数字。")
            return

        hotkey = self.v_hotkey.get().strip()
        if hotkey:
            try:
                parse(hotkey)
            except ValueError as exc:
                messagebox.showerror(APP_TITLE, f"急停键写法不对：{exc}")
                return

        self.stop_event.clear()
        self._busy(True)
        self._open_log(
            f"# 目标 {target}   今日好运 {self.v_lucky.get()}",
            f"# 重开上限 {max_attempts or '不限'}   战力点上限 {max_bp or '不限'}   "
            f"只读 {'是' if self.v_dry.get() else '否'}   快速 {'是' if self.v_fast.get() else '否'}",
            f"# 急停键 {hotkey or '无'}   管理员 {is_admin()}")
        self._append(f"{applog.stamp()} ── 开始：{target}  "
                     f"重开上限={max_attempts or '不限'}  战力点上限={max_bp or '不限'}"
                     + ("  快速模式" if self.v_fast.get() else "")
                     + ("  只读" if self.v_dry.get() else ""))

        if self.v_minimize.get():
            self.iconify()

        self.worker = threading.Thread(
            target=self._worker, daemon=True,
            args=(target, lucky, max_attempts, max_bp, self.v_dry.get(),
                  self.v_fast.get(), self.v_hotkey.get().strip()))
        self.worker.start()

    def _worker(self, target, lucky, max_attempts, max_bp, dry, fast, hotkey):
        try:
            from bqyw.screen import Capture
            cap = Capture()
            bot = Bot(cap, target, lucky=lucky, dry_run=dry,
                      max_attempts=max_attempts, max_bp=max_bp, fast_mode=fast,
                      logger=lambda s: self.q.put(("log", s)),
                      on_state=lambda st: self.q.put(("state", st)),
                      stop_flag=self.stop_event)
            bot.keep_console = True
            bot.hotkey = hotkey
            reason = bot.run()
            self.q.put(("done", (reason, bot)))
        except Abort as exc:
            self.q.put(("log", f"{applog.stamp()} [!] {exc}"))
            self.q.put(("done", ("aborted", None)))
        except Exception as exc:                       # noqa: BLE001
            if self.log_file is not None:              # traceback 只进文件，不糊屏
                try:
                    self.log_file.write(traceback.format_exc())
                    self.log_file.flush()
                except OSError:
                    pass
            self.q.put(("log", f"{applog.stamp()} [!] 异常：{exc!r}"))
            self.q.put(("done", ("error", None)))

    def _stop(self):
        self.stop_event.set()
        self.v_status.set("停止中…")

    def _on_close(self):
        self.stop_event.set()
        self._close_log()
        self.destroy()

    def _once(self):
        try:
            if self.log_file is None:                  # 没在跑就借一份，方便排查
                self._open_log("# 读一帧（诊断）")
            from bqyw.screen import Capture
            from bqyw.vision import TemplateSet, looks_like_draw_screen
            cap = Capture()
            scale = cap.layout()                       # 按实际画面尺寸重算坐标
            w, h = cap.frame_size()
            frame = cap.grab()
            ts = TemplateSet()
            cards = ts.read_hand(frame)
            self._append(f"{applog.stamp()} ── 读一帧：窗口状态 {cap.status()}，"
                         f"画面 {w}×{h}（坐标缩放 {scale:.3f}）")
            self._append(f"   像抽牌界面 {looks_like_draw_screen(frame)}")
            self._append(f"   轮次 {ts.read_round(frame)}，{len(cards)} 张 "
                         f"{[(c.point, c.suit) for c in cards]}")
            from bqyw.rules import best_hand
            self.q.put(("state", {
                "round": ts.read_round(frame),
                "count": len(cards),
                "points": sum(c.point for c in cards),
                "hand": HAND_NAME_CN[best_hand(cards)] if cards else "—",
            }))
        except Exception as exc:                       # noqa: BLE001
            self._append(f"{applog.stamp()} [!] 读帧失败：{exc!r}")

    # ------------------------------------------------------------ 事件泵
    def _pump(self):
        try:
            while True:
                kind, payload = self.q.get_nowait()
                if kind == "log":
                    self._append(payload)
                elif kind == "state":
                    st = payload
                    self.v_state.set(
                        f"轮次 {st.get('round') or '—'}   "
                        f"张数 {st.get('count')}   "
                        f"战力点 {st.get('points')}   "
                        f"牌型 {st.get('hand')}")
                    self.v_tally.set(
                        f"第 {st.get('attempts')} 轮   最好 {st.get('best_seen')}   "
                        f"最高 {st.get('best_score')}")
                elif kind == "estimate":
                    key, target, fast, n = payload
                    self._show_estimate(key, target, fast, n)
                elif kind == "done":
                    reason, bot = payload
                    if self.state() == "iconic":
                        self.deiconify()
                    self._busy(False)
                    self._append(f"{applog.stamp()} ── 结束（{reason}）"
                                 + (f"  {bot.pace()}" if bot else ""))
                    self._close_log()
        except queue.Empty:
            pass
        self.after(120, self._pump)


def _fatal(exc: BaseException) -> None:
    """打包成 --noconsole 的 exe 后没有控制台，崩溃时得自己把话说出来。"""
    import traceback
    text = "".join(traceback.format_exception(type(exc), exc, exc.__traceback__))
    try:
        path, fh = applog.new_log(header=applog.env_header() + "\n# 启动失败")
        fh.write(text)
        fh.close()
        where = str(path)
    except OSError:
        where = "（日志也写不出来）"
    try:
        ctypes.windll.user32.MessageBoxW(
            None, f"{APP_TITLE} 没能启动：\n\n{exc!r}\n\n详细堆栈：{where}",
            APP_TITLE, 0x10)
    except Exception:                                  # noqa: BLE001
        pass


def _selftest() -> int:
    """不开窗口，把关键路径跑一遍：exe --selftest

    打包后最怕的是「资源没打进去」「某个依赖漏了」，这个自检能把话说清楚，
    报告写到 logs/ 下，用户反馈时直接发过来。
    """
    rows: list[str] = []
    bad = 0

    def probe(name, fn):
        nonlocal bad
        try:
            rows.append(f"[ok]   {name}：{fn()}")
        except Exception as exc:                       # noqa: BLE001
            bad += 1
            rows.append(f"[FAIL] {name}：{exc!r}")

    def check_templates():
        from bqyw.vision import TemplateSet
        ts = TemplateSet()
        miss = ts.missing
        if miss["digit"] or miss["suit"] or not ts.rounds:
            raise RuntimeError(f"模板缺口 {miss} round={sorted(ts.rounds)}")
        return (f"{ts.dir}（数字 {sorted(ts.digits)} / 花色 {list(ts.suits)}"
                f" / 轮次 {sorted(ts.rounds)}）")

    def check_deps():
        import tkinter
        import cv2
        import mss
        import numpy as np
        import pydirectinput  # noqa: F401
        import win32gui  # noqa: F401
        import win32process  # noqa: F401
        return (f"cv2 {cv2.__version__} / numpy {np.__version__} / "
                f"mss {mss.__version__} / tkinter {tkinter.TkVersion} / "
                f"pywin32 ok / pydirectinput ok")

    probe("版本", lambda: __version__)
    probe("运行方式", lambda: "exe" if paths.is_frozen() else "python")
    probe("程序目录", paths.app_dir)
    probe("资源目录", paths.bundle_dir)
    probe("模板", check_templates)
    probe("依赖", check_deps)
    probe("管理员", is_admin)
    probe("日志目录", applog.log_dir)
    def check_hotkey():
        vk, mods = parse("f11")
        return f"f11 → vk {vk}" + (f" +{sorted(mods)}" if mods else "")

    def check_geometry():
        from bqyw import geometry as G
        base = G.BTN_DRAW
        s = G.use_resolution(1920, 1080)                # 缩放往返，验证坐标引擎
        hd_center = G.BTN_DRAW.center
        G.reset_resolution()
        if G.BTN_DRAW != base or abs(s - 0.75) > 1e-6:
            raise RuntimeError(f"缩放对不上：{G.BTN_DRAW} / {s}")
        return (f"基准 {G.BASE_W}×{G.BASE_H}，1080p ×{s:.3f}"
                f"（抽牌按钮 {hd_center}），复原正常")

    probe("急停键", check_hotkey)
    probe("坐标基准", check_geometry)

    text = "\n".join(rows) + f"\n\n结论：{'通过' if not bad else f'{bad} 项失败'}\n"
    try:
        path, fh = applog.new_log(header=applog.env_header() + "\n# 自检")
        fh.write(text)
        fh.close()
        where = str(path)
    except OSError as exc:
        where = f"（写不出日志：{exc!r}）"
    print(text)
    print(f"报告：{where}")
    return 0 if not bad else 1


def main():
    # --noconsole 打包后 sys.stdout / sys.stderr 是 None，print 会直接炸
    if sys.stdout is None or sys.stderr is None:
        sink = open(os.devnull, "w", encoding="utf-8")
        sys.stdout = sys.stdout or sink
        sys.stderr = sys.stderr or sink
    if "--selftest" in sys.argv:
        raise SystemExit(_selftest())
    try:
        App().mainloop()
    except BaseException as exc:                       # noqa: BLE001
        _fatal(exc)
        raise


if __name__ == "__main__":
    main()
# -*- coding: utf-8 -*-
"""抽牌状态机：抽 → 判定 → 达不到就重开 → 命中就停。"""
from __future__ import annotations

import threading
import time
from dataclasses import dataclass, field

import numpy as np

from . import actions, geometry as G, privilege
from .deck import MAX_DRAWS
from .rules import (HAND_NAME_CN, MULT, Hand, all_hands, best_hand, score)
from .targets import HandTarget, TargetBase
from .vision import (TemplateSet, card_band, count_cards_band, expected_highlight, find_highlight,
                     is_draw_enabled, looks_like_draw_screen, modal_confirm_visible,
                    )


class Abort(Exception):
    """发现不能继续时的急停信号。"""


def _minimize_console() -> None:
    """把自己的控制台最小化，避免它挡住游戏（点击是屏幕绝对坐标，会点到控制台上）。"""
    try:
        import ctypes
        hwnd = ctypes.windll.kernel32.GetConsoleWindow()
        if hwnd:
            ctypes.windll.user32.ShowWindow(hwnd, 6)      # SW_MINIMIZE
    except Exception:
        pass


def modal_open(frame: np.ndarray) -> bool:
    """放弃确认弹窗：靠那颗黄色「确认」按钮判定。"""
    b = G.MODAL_CONFIRM
    patch = frame[b.y1:b.y2, b.x1:b.x2]
    if patch.size == 0:
        return False
    return modal_confirm_visible(patch)


@dataclass
class Stats:
    attempts: int = 0
    draws: int = 0
    best_seen: Hand = Hand.HIGH
    best_score: int = 0
    mismatch: int = 0
    history: list = field(default_factory=list)


class Bot:
    def __init__(self, capture, target: Hand, lucky: Hand | None = None,
                 auto_start: bool = False, dry_run: bool = False,
                 max_attempts: int = 0, verbose: bool = True, poll: float = 0.25,
                 max_bp: int = 0, fast_mode: bool = False,
                 logger=None, on_state=None, stop_flag=None):
        self.cap = capture
        self.ts = TemplateSet()
        self.target = target if isinstance(target, TargetBase) else HandTarget(target)
        self.lucky = lucky
        self.auto_start = auto_start
        self.dry_run = dry_run
        self.max_attempts = max_attempts
        self.max_bp = max_bp            # >0 时，战力点超过它就主动放弃重开
        self.fast_mode = fast_mode      # 连点 5 张再扫一次（放弃提前剪枝换吞吐）
        self.fast_seconds = 6.0         # 快速模式狂点时长
        self.verbose = verbose
        self.poll = poll
        self.logger = logger            # GUI 用：把日志往队列里丢
        self.on_state = on_state        # GUI 用：每次读到状态就回调
        self.stop_flag = stop_flag or threading.Event()   # 置位即收工
        self.hotkey = "f11"             # 全局急停热键，空字符串表示不启用
        self._hot = None
        self.stats = Stats()
        self.stop_reason = ""
        self.t0 = 0.0
        self.force = False
        self.keep_console = False
        self._last_cards: list = []
        self._draw_fail = 0
        self._grace = 0              # >0 时跳过若干帧的结构自检（放弃后的淡出期）
        self._armed = False          # 本轮的「重开次数」已经记过账了没

    # ---------------------------------------------------------- 小工具
    def log(self, msg: str):
        line = f"[{time.strftime('%H:%M:%S')}] {msg}"
        if self.logger:
            self.logger(line)
        elif self.verbose:
            print(line, flush=True)

    def stopped(self) -> bool:
        return bool(self.stop_flag and self.stop_flag.is_set())

    def emit(self, **kw):
        if not self.on_state:
            return
        kw.setdefault("attempts", self.stats.attempts)
        kw.setdefault("draws", self.stats.draws)
        kw.setdefault("best_seen", HAND_NAME_CN[self.stats.best_seen])
        kw.setdefault("best_score", self.stats.best_score)
        try:
            self.on_state(kw)
        except Exception:
            pass

    def act(self, box, what: str):
        self.log(f"   -> {what} @{box.center}")
        if self.dry_run:
            return
        # 点击用的是屏幕绝对坐标：游戏一旦被盖住，这一下就点到别人身上了
        if self.cap.hwnd and not self.cap.foreground():
            raise Abort("游戏不在前台，已停止点击（避免点到别的窗口）")
        actions.click_box(box, origin=self.cap.origin())

    def frame(self):
        return self.cap.wait_stable()

    def read_state(self, expect=None, tries: int = 4, gap: float = 0.03):
        """又快又稳地读一帧。

        实测翻牌动画约 1.3s，动画中途也能扫到数字残影，所以不能只读一次；
        但也不需要"整屏稳定"，那等于白等动画播完——连续两帧读数一致就够。

        expect 传入上一次的结果：如果这一帧和它一致就直接返回，
        省掉第二次采样（刚 wait 完的那一轮状态必然没变，这一步能省 2/3 的读取开销）。
        """
        prev = (tuple((c.point, c.suit) for c in expect) if expect is not None else None)
        frame = self.cap.grab()
        cards = self.ts.read_hand(frame)
        for _ in range(tries):
            key = tuple((c.point, c.suit) for c in cards)
            if key == prev:
                return frame, cards
            prev = key
            time.sleep(gap)
            frame = self.cap.grab()
            cards = self.ts.read_hand(frame)
        return frame, cards

    def wait_cards(self, expect: int, timeout: float = 3.0) -> list:
        """等卡槽翻到至少 expect 张。用裸抓帧 + 手牌一致判定，不做整屏稳定等待。"""
        deadline = time.time() + timeout
        prev = None
        while time.time() < deadline:
            cards = self.ts.read_hand(self.cap.grab())
            key = tuple((c.point, c.suit) for c in cards)
            if key == prev and len(cards) >= expect:
                return cards
            prev = key
            if self.stopped():
                break
            time.sleep(0.05)
        return self.ts.read_hand(self.cap.grab())

    def wait_draw_ready(self, timeout: float = 2.5) -> bool:
        """等「抽牌」按钮从冷却里恢复（按钮变亮），最多等 timeout 秒。"""
        deadline = time.time() + timeout
        while time.time() < deadline:
            if is_draw_enabled(self.cap.grab()):
                return True
            if self.stopped():
                return False
            time.sleep(0.04)
        return False

    def draw(self, before: int, timeout: float = 2.5) -> int:
        """点一次抽牌并等新牌翻出来；没翻出来就强制抬起鼠标并记账。"""
        if not self.wait_draw_ready():
            self.log("   [!] 抽牌按钮一直没恢复可用，稍后再试")
        self.act(G.BTN_DRAW, "抽牌")
        self.stats.draws += 1
        cards = self.wait_cards(before + 1, timeout=timeout)
        if len(cards) > before:
            self._draw_fail = 0
            return len(cards)

        if self.dry_run:
            return before          # 只读模式本来就不会翻牌，别当成故障
        if self.stopped():
            return before          # 急停打断了这一次，不是抽牌失败
        self._draw_fail += 1
        actions.release_stuck()
        self.log(f"   [!] 抽牌后没有翻出新牌（连续第 {self._draw_fail} 次）"
                 f"，已强制抬起鼠标左键")
        if self._draw_fail >= 2:
            raise Abort(
                "抽牌连续没反应。常见原因：鼠标左键卡在按下状态，"
                "或游戏窗口没真正接收合成点击。已停止，请手动点一下游戏再重开。")
        time.sleep(0.3)
        return before

    def draw_burst(self, seconds: float = 6.0, want: int = MAX_DRAWS) -> list:
        """快速模式：对着抽牌按钮**连续狂点**固定时长，最后统一扫一次。

        游戏在冷却期会把多出来的点击直接丢掉，不会排队，所以不需要等按钮变亮 ——
        狂点即可，实测 6 秒足够铺满 5 张（手测 5.61s）。省掉的是"每张牌都要等渲染
        再扫一遍"的开销；代价是做不了提前剪枝，而且 5 张牌 = 5 个敌人。
        """
        if self.dry_run:
            self.log("   -> 快速模式（只读，不点击）")
            return list(self._last_cards)

        if self.cap.hwnd and not self.cap.foreground():
            raise Abort("游戏不在前台，已停止点击（避免点到别的窗口）")

        t0 = time.time()
        clicks = 0
        origin = self.cap.origin()
        self.log(f"   -> 快速连点 {seconds:.1f}s @{G.BTN_DRAW.center}")
        while time.time() - t0 < seconds and not self.stopped():
            actions.click_box(G.BTN_DRAW, origin=origin)
            clicks += 1
        self.stats.draws += clicks
        elapsed = time.time() - t0
        self.log(f"      连点 {clicks} 次 / 用时 {elapsed:.1f}s")

        # 等最后一张翻出来：只看卡头条带（4ms/次），比整屏读牌快一个数量级
        deadline = time.time() + 2.0
        while time.time() < deadline and self.board_count() < want:
            if self.stopped():
                break
            time.sleep(0.008)
        cards = self.read_state(expect=None)[1]
        if len(cards) < want:
            # 最后一张可能还在翻，正经等一会儿；等不到才报
            cards = self.wait_cards(want, timeout=2.0)
        if len(cards) < want:
            self.log(f"   [!] 只铺到 {len(cards)} 张（预期 {want}），按现有手牌判定")
        return cards

    # ---------------------------------------------------- 小区域快查
    def modal_visible(self) -> bool:
        b = G.MODAL_CONFIRM
        return modal_confirm_visible(
            self.cap.grab_region(b.x1, b.y1, b.x2, b.y2))

    def board_count(self) -> int:
        x1, y1, x2, y2 = card_band()
        return count_cards_band(self.cap.grab_region(x1, y1, x2, y2))

    def wait_board_clear(self, timeout: float = 2.0) -> bool:
        """等牌清空。只抓卡头条带，几毫秒一次，连续两次为空才算数。"""
        deadline = time.time() + timeout
        seen = 0
        while time.time() < deadline:
            if self.board_count() == 0:
                seen += 1
                if seen >= 2:
                    return True
            else:
                seen = 0
            if self.stopped():
                return False
            time.sleep(0.012)
        return False

    def dismiss_modal(self) -> None:
        time.sleep(0.06)
        self.act(G.MODAL_CONFIRM, "确认放弃")
        self.wait_board_clear()

    def forfeit(self, timeout: float = 2.0, wait_clear: bool = True) -> None:
        """放弃挑战 → 确认 → （可选）等清空，一把做完，不来回过主循环。

        快速模式下 wait_clear=False：清空动画交给下一步的连点去吃掉，
        过渡期的几次点击游戏会直接丢弃，不影响结果，白白省下 0.28 秒。
        """
        t0 = time.time()
        self.stats.history.append(self._last_cards)
        self.act(G.BTN_FORFEIT, "放弃挑战")
        t1 = time.time()
        deadline = time.time() + timeout
        while time.time() < deadline:
            if self.modal_visible():
                break
            if self.stopped():
                return
            time.sleep(0.008)
        t2 = time.time()
        # 黄色按钮刚显现时命不中：等动画落位再点，否则这一下会被吞掉，
        # 只能靠主循环补救 —— 那一下要多花 1.5 秒，远不如等这 60ms。
        time.sleep(0.06)
        self.act(G.MODAL_CONFIRM, "确认放弃")
        t3 = time.time()
        if wait_clear:
            self.wait_board_clear()
        t4 = time.time()
        self._last_cards = []
        if not wait_clear:
            # 快速模式没等清空：接下来一两帧是淡出动画，结构自检必然误判，先放行
            self._grace = 2
        if self.verbose:
            self.log(f"   （放弃 {t4 - t0:.2f}s = 点击 {t1 - t0:.2f}"
                     f" + 等弹窗 {t2 - t1:.2f} + 确认 {t3 - t2:.2f}"
                     f" + 等清空 {t4 - t3:.2f}）")

    # ---------------------------------------------------------- 主循环
    def pace(self) -> str:
        seconds = max(0.0, time.time() - self.t0)
        n = max(1, self.stats.attempts)
        base = (f"用时 {seconds:.0f}s，{self.stats.attempts} 轮，"
                f"平均 {seconds / n:.1f}s/轮，{self.stats.draws} 次抽牌")
        if self.stats.attempts and not self.fast_mode:
            base += f"，{seconds / max(1, self.stats.draws):.2f}s/抽"
        if self.stats.mismatch:
            base += f"（表格抢跑 {self.stats.mismatch} 次，属正常）"
        return base

    def _on_hotkey(self):
        if not self.stop_flag.is_set():
            self.log(f"\n[急停] 收到 {str(self.hotkey).upper()}，正在停手…")
            self.stop_flag.set()

    def _start_hotkey(self):
        if not self.hotkey:
            return
        try:
            from .hotkey import HotkeyListener
            self._hot = HotkeyListener(self.hotkey, self._on_hotkey, logger=self.log)
            self._hot.start()
            self._hot.ready.wait(1.5)
            if self._hot.failed:
                self.log(f"   [!] {self._hot.failed}（急停热键不可用）")
                self._hot = None
            else:
                self.log(f"急停热键：{str(self.hotkey).upper()}（全局生效，按一下就停）")
        except ValueError as exc:
            self.log(f"   [!] 热键写法不对：{exc}")
            self._hot = None
        except Exception as exc:                       # noqa: BLE001
            self.log(f"   [!] 热键注册异常：{exc!r}")
            self._hot = None

    def run(self):
        try:
            return self._run()
        finally:
            if self._hot is not None:
                self._hot.stop()

    def _run(self):
        self.t0 = time.time()
        self._start_hotkey()
        self.log(f"目标：{self.target}")
        miss = self.ts.missing
        if miss["digit"] or miss["suit"]:
            need = []
            if miss["digit"]:
                need.append(f"点数 {miss['digit']}")
            if miss["suit"]:
                need.append(f"花色 {miss['suit']}")
            self.log("  [!] 模板缺口：" + "，".join(need) + " —— 遇到认不出的牌会停下来")
        self.log(f"抓取源：{self.cap.title}" + ("（只读模式）" if self.dry_run else "")
                 + ("（快速模式：连抽 5 张）" if self.fast_mode else ""))

        # 坐标按实际画面尺寸缩放：换分辨率后所有 Box 都要重算
        try:
            s = self.cap.layout()
        except G.UnsupportedResolution as exc:
            self.log(f"\n[!] {exc}")
            self.stop_reason = "bad_resolution"
            return self.stop_reason
        w, h = self.cap.frame_size()
        self.log(f"画面 {w}×{h}，坐标缩放 {s:.3f}"
                 + ("（基准分辨率）" if abs(s - 1.0) < 1e-6 else ""))

        # 提权自检：游戏多为提权启动，UIPI 会静默丢弃低权限进程的点击
        info = privilege.check_target(self.cap.hwnd)
        self.log(f"权限：本进程 admin={info['self_admin']}  "
                 f"游戏提权={info['target_elevated']}")
        if not self.dry_run and not info["self_admin"] and not self.force:
            if info["target_elevated"] is not False:
                self.log("\n[!] 本进程没有管理员权限，而游戏进程权限更高。")
                self.log("    此时点击会被系统**静默丢弃**（点了没反应，也不报错）。")
                self.log("    请用管理员身份重开：在管理员终端里跑，或加 --auto-elevate。")
                self.stop_reason = "need_admin"
                return self.stop_reason
            self.log("[!] 游戏进程不是管理员，但控制游戏仍建议提权，继续尝试。")

        if self.cap.hwnd:
            if self.dry_run:
                if not self.cap.foreground():
                    self.log("[!] 游戏不在前台：只读模式抓到的是挡在它前面的窗口。")
                    self.log("    请把游戏切到最前面再看判定。")
            else:
                if not self.keep_console:
                    _minimize_console()
                    self.log("（已把本控制台最小化——它压在游戏上会让抓屏和点击都出错）")
                actions.activate_window(self.cap.hwnd)
                time.sleep(0.4)
                if not self.cap.foreground():
                    self.log("\n[!] 没能把游戏窗口提到前台 —— 这时点击会落到别的窗口上，已停止。")
                    self.stop_reason = "not_foreground"
                    return self.stop_reason

        bad_frames = 0
        while True:
            if self.stopped():
                self.stop_reason = "stopped"
                self.log("已按你的要求停止。")
                return self.stop_reason
            if self._grace > 0:
                # 刚放弃完，屏幕还在淡出：等画面稳住再读，跳过弹窗/结构判定
                self._grace -= 1
                frame = self.cap.wait_stable(timeout=0.8)
                cards = self.ts.read_hand(frame)
                if looks_like_draw_screen(frame):
                    self._grace = 0
            else:
                # 弹窗必须最先判：确认框会把背景模糊+压暗，结构自检在那里必然误判
                if self.modal_visible():
                    self.log("   发现放弃确认弹窗，先确认掉")
                    self.dismiss_modal()
                    continue

                frame, cards = self.read_state(expect=self._last_cards)

            # 画面自检：挡住/最小化/不在抽牌页时，宁可停下也不瞎点
            if not looks_like_draw_screen(frame):
                bad_frames += 1
                if bad_frames >= 3:
                    self.log("\n[!] 抓到的画面不是抽牌界面。")
                    self.log(f"    窗口状态：{self.cap.status()}")
                    self.log("    常见原因：游戏被别的窗口挡住 / 最小化 / 当前不在抽牌页 /")
                    self.log("              终端窗口压在了抽牌界面上（会同时导致点击点错窗口）。")
                    self.stop_reason = "bad_frame"
                    return self.stop_reason
                time.sleep(0.25)
                continue
            bad_frames = 0
            self._last_cards = cards

            # 交叉校验（只统计，不拦路）：表格高亮在换数时会滚动，比手牌慢半拍，
            # 所以对不上是常态，不能拿它当硬门禁。真出现持续错位会在结尾报出来。
            if cards:
                want = expected_highlight(sum(c.point for c in cards))
                got = find_highlight(frame)
                if want is not None and got is not None and got != want:
                    self.stats.mismatch += 1

            n = len(cards)
            rnd = self.ts.read_round(frame) if self.ts.rounds else 1
            if rnd is None:
                # 淡出/翻牌动画里轮次数字可能读不出，别急着当成"第二轮"停下
                for _ in range(4):
                    time.sleep(0.05)
                    rnd = self.ts.read_round(self.cap.grab())
                    if rnd is not None:
                        break
            self.emit(round=rnd, count=n, cards=cards,
                      points=sum(c.point for c in cards),
                      hand=HAND_NAME_CN[best_hand(cards)] if cards else "—")

            # ---- 战绩（命中也要算进去，否则摘要里少了最好那一手）
            if cards:
                self.stats.best_seen = max(self.stats.best_seen, best_hand(cards),
                                           key=lambda h: MULT[h])
                self.stats.best_score = max(self.stats.best_score,
                                            score(cards, self.lucky))

            # ---- 命中
            if cards and self.target.matches(cards):
                self.stats.history.append(cards)
                self.log(f"\n命中 {self.target}："
                         f"{[(c.point, c.suit) for c in cards]}  "
                         f"战力点 {sum(c.point for c in cards)}  "
                         f"预估奖励点 {score(cards, self.lucky)}")
                self._beep()
                if self.auto_start and not self.dry_run:
                    self.act(G.BTN_START, "开始挑战")
                self.stop_reason = "hit"
                return "hit"

            # ---- 第二轮不能重开（放弃=退出整个演算），一律交还给你
            if rnd != 1:
                where = f"第 {rnd} 轮" if rnd else "读不出当前轮次"
                self.log(f"\n{where}：这里有牌时「放弃挑战」等于退出整场演算，"
                         f"不会清牌重来。已停止，剩下的交给你。")
                self.stop_reason = "round2" if rnd == 2 else "round_unknown"
                return self.stop_reason

            # ---- 空手：开新一轮
            # 放弃后第一次点击常被"重新发牌"的过场吃掉，牌还是空的，于是下一圈
            # 又会走到这里。用 _armed 保证一轮只记一次账，否则重开上限会翻倍地耗。
            if n == 0:
                if not self._armed:
                    if self.max_attempts and self.stats.attempts >= self.max_attempts:
                        self.log(f"\n达到重开上限 {self.max_attempts}，停止")
                        self.stop_reason = "max_attempts"
                        return "max_attempts"
                    self.stats.attempts += 1
                    self._armed = True
                    if self.stats.attempts % 10 == 1:
                        self.log(f"[第 {self.stats.attempts} 轮]")
                if self.fast_mode and self.target.stop_count >= MAX_DRAWS:
                    # 快速模式：别先单抽一张白等 2 秒，直接开连点
                    self.draw_burst(self.fast_seconds, MAX_DRAWS)
                    continue
                self.draw(0)
                continue

            # ---- 有牌：判断还要不要继续
            self._armed = False          # 见到牌了，下一次空手才算新的一轮
            best = best_hand(cards)
            self.log(f"   [{n}张] {[(c.point, c.suit) for c in cards]} "
                     f"牌型={HAND_NAME_CN[best]} 战力点={sum(c.point for c in cards)}")

            # 战力点上限：超过就注定打不动，直接重开，别浪费时间
            if self.max_bp and sum(c.point for c in cards) > self.max_bp:
                self.log(f"     战力点 {sum(c.point for c in cards)} > 上限 {self.max_bp}，放弃")
                self.forfeit()
                continue

            stop = self.target.stop_count          # 自定义目标可能只要前几张
            left = stop - n
            if left <= 0:
                self.forfeit(wait_clear=not self.fast_mode)
                if self.fast_mode and not self.stopped():
                    # 确认之后不用等淡出/清空动画，直接开下一轮连点：
                    # 抽牌按钮就算被动画挡着也照样吃点击，过渡期丢掉几下无所谓
                    self._grace = 0
                    self.draw_burst(self.fast_seconds, MAX_DRAWS)
                continue

            # 快速模式：不做可行性剪枝，直接连抽到 5 张再统一判定。
            # 但自定义目标只要前几张时绝不能连点，多抽一张就废了，只能一张一张来。
            if self.fast_mode and stop >= MAX_DRAWS:
                self.draw_burst(self.fast_seconds, MAX_DRAWS)
                continue

            if not self.target.possible(cards, left):
                self.log(f"     {self.target} 已不可能，提前放弃")
                self.forfeit()
                continue

            self.draw(n)

    @staticmethod
    def _beep():
        try:
            import winsound
            for _ in range(3):
                winsound.Beep(900, 120)
                time.sleep(0.05)
        except Exception:
            pass


def dry_analyse(frame, target, lucky: Hand | None = None) -> dict:
    """不动手，只把画面读出来并给出建议。"""
    tgt = target if isinstance(target, TargetBase) else HandTarget(target)
    ts = TemplateSet()
    cards = ts.read_hand(frame)
    n = len(cards)
    total = sum(c.point for c in cards)
    left = MAX_DRAWS - n
    reach = 1.0 if tgt.possible(cards, left) else 0.0
    return {
        "cards": cards,
        "count": n,
        "sum": total,
        "table_bp": expected_highlight(total) if n else None,
        "hands": sorted(all_hands(cards), key=lambda h: h.value) if cards else [],
        "best": best_hand(cards) if cards else Hand.HIGH,
        "score": score(cards, lucky) if cards else 0,
        "reach_p": reach,
        "target": tgt,
        "modal": modal_open(frame),
        "highlight": find_highlight(frame),
        "round": ts.read_round(frame) if ts.rounds else None,
    }

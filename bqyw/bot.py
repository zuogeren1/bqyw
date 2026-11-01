# -*- coding: utf-8 -*-
"""抽牌状态机：抽 → 判定 → 达不到就重开 → 命中就停。"""
from __future__ import annotations

import ctypes
import threading
import time
from dataclasses import dataclass, field

import cv2
import numpy as np

from . import actions, applog, enemies as enemy_mod, geometry as G, privilege, screen as screenmod
from .deck import MAX_DRAWS
from .rules import (HAND_NAME_CN, MULT, Hand, all_hands, best_hand, score)
from .targets import HandTarget, TargetBase
from .vision import (TemplateSet, card_band, count_cards_band, expected_highlight,
                     find_highlight, looks_like_draw_screen, modal_confirm_visible,
                     )


MEM_REPORT_SECONDS = 300        # 每隔这么久往日志里写一行内存
MAX_HISTORY = 200               # 战绩里最多留几手牌（不参与判定，只供回头查）

# 游戏抽牌有冷却：5 张最快 6 秒，一张约 1.2 秒，冷却期的点击会被直接丢掉。
#
# 实测教训（两版都栽在这上面）：
#   * 按钮视觉靠不住：亮着照样点空、暗着反而能点；
#   * 补点间隔贴着冷却（1.15s）或只等按钮就点，都会大面积点空，
#     每点空一次就要白等一个周期，单张反而涨到 1.8~2.1 秒。
# 所以改成「按固定节拍点，不看按钮」：距上一次点击满 CLICK_DELAY 才点下一次；
# 点完 CARD_LATENCY 还没出牌，说明这一下被冷却吃了，就把间隔调大一点再试。
# 实测（连点铺满 5 张的旧测法）：5 张 6.0 秒 = 4 × 1.38 + 0.47（出牌延迟），
# 也就是**真实冷却约 1.38 秒**，不是 1.2 秒。1.2~1.25 秒去点必然点空，
# 而每点空一次要多等一个完整周期 —— 得不偿失。
CLICK_DELAY = 1.45          # 起始节拍：冷却 ≈1.38，留 70ms 余量
CLICK_DELAY_MIN = 1.40      # 缓慢回收到这里就停，不再去试冷却边界
CLICK_DELAY_MAX = 2.30      # 冷却比预想更长时，慢慢加到这里
CLICK_DELAY_STEP_UP = 0.15  # 点空一次加这么多
CLICK_DELAY_STEP_DOWN = 0.01  # 每出一张牌回收这么点
CARD_LATENCY = 0.90     # 点完超过这么久还没出牌，判定这一下点空了
DRAW_WINDOW = 6.0       # 一次抽牌最多折腾这么久（≈5 张的冷却总量）
ROUND_WAIT = 1.5        # 放弃之后等牌面清空的上限


class Abort(Exception):
    """发现不能继续时的急停信号。"""


class _MEMCOUNTERS(ctypes.Structure):
    """PROCESS_MEMORY_COUNTERS_EX。psapi 的称为 EX 的那个尾巴多一个 PrivateUsage。"""

    _fields_ = [
        ("cb", ctypes.c_ulong), ("PageFaultCount", ctypes.c_ulong),
        ("PeakWorkingSetSize", ctypes.c_size_t), ("WorkingSetSize", ctypes.c_size_t),
        ("QuotaPeakPagedPoolUsage", ctypes.c_size_t), ("QuotaPagedPoolUsage", ctypes.c_size_t),
        ("QuotaPeakNonPagedPoolUsage", ctypes.c_size_t), ("QuotaNonPagedPoolUsage", ctypes.c_size_t),
        ("PagefileUsage", ctypes.c_size_t), ("PeakPagefileUsage", ctypes.c_size_t),
        ("PrivateUsage", ctypes.c_size_t),
    ]


def meminfo() -> tuple[float, float, float] | None:
    """(工作集 MB, 提交 MB, 峰值工作集 MB)；调用失败返回 None。"""
    try:
        k32 = ctypes.windll.kernel32
        # 必须 OpenProcess：GetCurrentProcess() 的伪句柄是 -1，当成 c_int 传给
        # psapi 会变成一个无效句柄，调用失败还得不出原因
        h = k32.OpenProcess(0x0400 | 0x0010, False, k32.GetCurrentProcessId())
        if not h:
            return None
        try:
            p = _MEMCOUNTERS()
            p.cb = ctypes.sizeof(p)
            if not ctypes.windll.psapi.GetProcessMemoryInfo(h, ctypes.byref(p), p.cb):
                return None
            mb = 2 ** 20
            return p.WorkingSetSize / mb, p.PrivateUsage / mb, p.PeakWorkingSetSize / mb
        finally:
            k32.CloseHandle(h)
    except Exception:                                  # noqa: BLE001
        return None


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
    draws: int = 0               # 真正翻出来的牌数（不是点击次数）
    clicks: int = 0              # 点了几下「抽牌」
    forfeits: int = 0
    best_seen: Hand = Hand.HIGH
    best_score: int = 0
    enemy_drops: int = 0          # 因为敌人名单而放弃的次数
    mismatch: int = 0
    gaps: list = field(default_factory=list)      # 相邻两张牌的间隔（秒）
    history: list = field(default_factory=list)


class Bot:
    def __init__(self, capture, target: Hand, lucky: Hand | None = None,
                 auto_start: bool = False, dry_run: bool = False,
                 max_attempts: int = 0, verbose: bool = True, poll: float = 0.25,
                 max_bp: int = 0, enemy_policy=None,
                 logger=None, on_state=None, stop_flag=None):
        self.cap = capture
        self.ts = TemplateSet()
        self.target = target if isinstance(target, TargetBase) else HandTarget(target)
        self.lucky = lucky
        self.auto_start = auto_start
        self.dry_run = dry_run
        self.max_attempts = max_attempts
        self.max_bp = max_bp            # >0 时，战力点超过它就主动放弃重开
        # 敌人名单（黑/白名单）：关着时完全不加载模板，零开销
        self.enemy = enemy_policy or enemy_mod.OFF
        self.book = None
        self._checked = 0               # 这一轮已经查过名单的牌数
        self._pending: dict[int, float] = {}   # 看不清的牌：槽位 → 目前最高分
        self._confirm: dict[int, tuple] = {}   # 看着像但分数不够硬的：槽位 → (敌人, 分数)
        self._dumps = 0                 # 已经存了几张"看不清"的卡面（一次运行最多 5 张）
        self._hit_score = 0.0           # 最近一次命中名单时的立绘分数（写日志用）
        self._enemy_streak = 0          # 连续因为名单放弃了几轮
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
        self._armed = False          # 本轮的「重开次数」已经记过账了没
        self._mem_at = 0.0           # 上次往日志里写内存的时间
        self._mem_last: tuple[float, float] | None = None
        self._last_card_at = 0.0     # 上一张牌出现的时间（算出牌间隔）
        self._forfeit_fail = 0       # 连续几次没点开放弃弹窗
        self._drops = 0              # 累计点空次数（调节拍用）
        self._fresh_round = False    # 刚放弃完：下一圈先等牌清空，再直接点第一张
        self._t = {"idle": 0.0, "click": 0.0, "card": 0.0,
                   "read": 0.0, "forfeit": 0.0}
        self._last_click_at = 0.0    # 上一次点「抽牌」的时刻（跨 draw 保持）
        self.click_delay = CLICK_DELAY
        self._t_logged = 0

    # ---------------------------------------------------------- 小工具
    def log(self, msg: str):
        line = f"[{time.strftime('%H:%M:%S')}] {msg}"
        if self.logger:
            self.logger(line)
        elif self.verbose:
            print(line, flush=True)

    def stopped(self) -> bool:
        return bool(self.stop_flag and self.stop_flag.is_set())

    def _remember(self, cards):
        """记一手牌。元素数封顶 —— 一晚上几千轮，不封顶就是一根慢慢涨的内存。"""
        self.stats.history.append(list(cards))
        if len(self.stats.history) > MAX_HISTORY:
            del self.stats.history[:len(self.stats.history) - MAX_HISTORY]

    def _mem_tick(self):
        """隔几分钟把内存写进日志。

        用户反馈"占内存"时，日志里就有现成的曲线；平时几乎不占篇幅
        （变化小于 16MB 就不写）。
        """
        now = time.time()
        if now - self._mem_at < MEM_REPORT_SECONDS:
            return
        self._mem_at = now
        info = meminfo()
        if info is None:
            return
        ws, commit, peak = info
        if (self._mem_last is not None
                and abs(ws - self._mem_last[0]) < 16
                and abs(commit - self._mem_last[1]) < 16):
            return
        self._mem_last = (ws, commit)
        self.log(f"[内存] 占用 {ws:.0f} MB（提交 {commit:.0f} MB，峰值 {peak:.0f} MB）")

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

    def read_state(self, expect=None, tries: int = 3, gap: float = 0.015):
        """又快又稳地读一帧。

        实测翻牌动画约 1.3s，动画中途也能扫到数字残影，所以不能只读一次；
        但也不需要"整屏稳定"，那等于白等动画播完——连续两帧读数一致就够。

        expect 传入上一次的结果（最好是刚抽到的那手牌）：一致就直接返回，
        省掉第二次采样。整屏只抓一次，认牌只转卡头条带那块灰度。
        """
        prev = (tuple((c.point, c.suit) for c in expect) if expect is not None else None)
        frame = self.cap.grab()
        cards = self.hand_of(frame)
        for _ in range(tries):
            key = tuple((c.point, c.suit) for c in cards)
            if key == prev:
                return frame, cards
            prev = key
            time.sleep(gap)
            frame = self.cap.grab()
            cards = self.hand_of(frame)
        return frame, cards

    def hand_of(self, frame) -> list:
        """从整屏里只裁卡头条带认牌。

        整帧转灰度是 3.7MB / 38ms，这条带子只有 700KB / 17ms —— 每次读牌都要走，
        差的就是每张牌 20ms。
        """
        x1, y1, x2, y2 = card_band()
        return self.ts.read_hand(frame[y1:y2, x1:x2], ox=x1, oy=y1)

    def hand_now(self) -> list:
        """只抓卡头条带读手牌。

        整屏抓一次是 14.7MB，这条条带只有 700KB。抽一张牌要轮询几十次，
        差的是几十倍的分配量 —— 堆被 14MB 级的大块反复冲刷，是内存上去下不来的
        主要原因之一。
        """
        x1, y1, x2, y2 = card_band()
        return self.ts.read_hand(self.cap.grab_region(x1, y1, x2, y2), ox=x1, oy=y1)

    def note_card(self, cards, before: int) -> None:
        """记一张新牌：张数、出牌间隔、失败计数。"""
        now = time.time()
        self.stats.draws += max(0, len(cards) - before)
        if self._last_card_at:
            self.stats.gaps.append(now - self._last_card_at)
            if len(self.stats.gaps) > 500:
                del self.stats.gaps[:-500]
        self._last_card_at = now
        self._draw_fail = 0
        # 出一张牌就回收一点点，但**不回到冷却边界以下**：
        # 之前每次减 0.02 一路滑到 1.25，然后每隔十来张就点空一次，来回抵消
        self.click_delay = max(CLICK_DELAY_MIN,
                               self.click_delay - CLICK_DELAY_STEP_DOWN)

    def _check_enemies(self, frame, cards) -> str | None:
        """把这一帧的牌对一遍敌人名单；确认命中就返回敌人名字。

        **不睡、不额外抓屏**：认不清的牌记进 _pending，等下一张牌翻出来、
        主循环本来就抓了新帧时，顺带和新牌一起认 —— 那时翻牌动画也早走完了。
        所以识别对节拍零影响：一帧只比一次，算的是同一份像素。

        **一眼不够清楚的两帧定案**：分数 ≥ 0.88 直接弃；0.78~0.88 之间先记进
        _confirm，下一帧还是同一个敌人才弃 —— 这一段多半是半张立绘，宁可晚弃一张。

        命中优先判新牌：新翻出来就撞名单的话，不用等旧牌确认就先弃。
        认不出就当没这个敌人（认不出不弃，绝不乱弃）。
        """
        if not self.enemy.active() or self.book is None or frame is None:
            return None
        if len(cards) < self._checked:          # 牌桌被清空过：上一轮没认出的作废
            self._flush_pending(frame)
            self._checked = 0

        # 这一帧要认的：新翻出来的 + 没认清的 + 看着像、等确认的
        todo = (list(range(self._checked, len(cards)))
                + sorted(self._pending) + sorted(self._confirm))
        self._checked = len(cards)
        for i in todo:
            if i >= len(cards) or cards[i].point <= 0:
                self._pending.pop(i, None)
                self._confirm.pop(i, None)
                continue
            name, score = self._match_enemy(frame, i, cards[i].point)
            if name is None:
                # 分数留着，只涨不跌：下一帧是更晚的画面，认得更清
                self._pending[i] = max(score, self._pending.get(i, 0.0))
                self._confirm.pop(i, None)
                continue
            self._pending.pop(i, None)
            if not self.enemy.should_drop(name):
                self._confirm.pop(i, None)      # 认出来了但不在名单里，不用再确认
                continue
            if score >= enemy_mod.CONFIRM_NCC:
                self._hit_score = score         # 一眼就够清楚
                return name
            if self._confirm.get(i, (None, 0.0))[0] == name:
                self._hit_score = score         # 下一帧还是同一个敌人 → 确认
                return name
            self._confirm[i] = (name, score)    # 先记下，下一帧再定
        return None

    def _match_enemy(self, frame, slot: int, point: int):
        """裁立绘比对，返回 (认出来的名字 or None, 分数)。"""
        box = enemy_mod.art_box(slot)
        patch = frame[box.y1:box.y2, box.x1:box.x2]
        hit, score = self.book.best(patch, point)
        if hit is None:
            return None, 0.0
        return (hit.name if score >= self.book.threshold else None), score

    def _flush_pending(self, frame=None) -> None:
        """一轮结束：还没认出来的牌，记一行日志（有画面就顺手存一张卡面）。"""
        for slot, score in sorted(self._pending.items()):
            self.log(f"     第 {slot + 1} 张一直没认出来（最像 {score:.2f}）")
            if frame is not None:
                self._dump_art(frame, slot, score)
        self._pending.clear()
        self._confirm.clear()

    def _dump_art(self, frame, slot: int, score: float) -> None:
        """认不清的卡面存一张（一次运行最多 5 张），出问题时直接发回来。"""
        if self._dumps >= 5:
            return
        box = enemy_mod.art_box(slot)
        patch = frame[box.y1:box.y2, box.x1:box.x2]
        if patch is None or patch.size == 0:
            return
        try:
            path = applog.log_dir() / f"enemy-{time.strftime('%H%M%S')}-第{slot + 1}张-{score:.2f}.png"
            cv2.imencode(".png", patch)[1].tofile(str(path))
        except Exception:                       # noqa: BLE001
            return
        self._dumps += 1
        self.log(f"     （这张卡面存了一份：{path.name}）")

    def draw(self, before: int, window: float = DRAW_WINDOW) -> list:
        """点「抽牌」并等新牌，直到手牌比 before 多一张。

        游戏一张牌约 1.2 秒冷却（5 张最快 6 秒），冷却期的点击会被直接丢掉。
        实测两种坑都踩过：
          * 只信按钮视觉 —— 它会骗人（亮着也点空、暗着也能点）
          * 补点间隔贴着冷却（1.15s）—— 大部分点击落在冷却里，白等一整个冷却
        所以这里：**第一下等按钮亮（最多 1.2s）再点；没出牌就等 1.35s 补一下**。
        补点间隔大于冷却，保证第二下必定落在可点窗口里。

        返回读到的手牌本身（不是张数）—— 主循环拿它当下一次的 expect，
        这样下一帧只需抓一次整屏就能确认。
        """
        deadline = time.time() + window
        while time.time() < deadline and not self.stopped():
            gap = time.time() - self._last_click_at
            if gap < self.click_delay:
                # 还没到节拍：直接睡到点。这里**不查手牌** —— 上一张已经读到了，
                # 等待期间不可能凭空多一张；每轮查一次要 26ms，全加在间隔上了。
                remain = self.click_delay - gap
                self._t["idle"] += min(remain, 0.05)
                time.sleep(min(remain, 0.05))
                continue

            # 计时从"开始点"算：一次点击本身要 110ms，算在间隔里会让每一张都慢 0.1s
            clicked_at = self._last_click_at = time.time()
            self.act(G.BTN_DRAW, "抽牌")
            self.stats.clicks += 1
            self._t["click"] += time.time() - clicked_at

            if self.dry_run:
                time.sleep(0.4)          # 只读模式不会真翻牌，别在这耗满 6 秒
                return self.hand_now()

            # 等这张牌：正常半秒左右翻出来，超过 CARD_LATENCY 就是点空了
            reveal = clicked_at + CARD_LATENCY
            while time.time() < reveal and not self.stopped():
                cards = self.hand_now()
                if len(cards) > before:
                    self._t["card"] += time.time() - clicked_at
                    self.note_card(cards, before)
                    return cards
                time.sleep(0.02)

            if self.click_delay < CLICK_DELAY_MAX:
                self.click_delay = min(CLICK_DELAY_MAX,
                                       self.click_delay + CLICK_DELAY_STEP_UP)
                self._drops += 1
                if self._drops <= 3 or self._drops % 10 == 0:
                    self.log(f"   [!] 这一下点空了，点击节拍调到 {self.click_delay:.2f}s"
                             f"（累计 {self._drops} 次）")

        if self.stopped():
            return self.hand_now()      # 急停打断了这一次，不是抽牌失败
        self._draw_fail += 1
        actions.release_stuck()
        self.log(f"   [!] {window:.0f}s 内没翻出新牌（连续第 {self._draw_fail} 次）"
                 f"，已强制抬起鼠标左键")
        if self._draw_fail >= 2:
            raise Abort(
                "抽牌连续没反应。常见原因：鼠标左键卡在按下状态，"
                "或游戏窗口没真正接收合成点击。已停止，请手动点一下游戏再重开。")
        return self.hand_now()

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

    def forfeit(self, timeout: float = 1.5) -> bool:
        """放弃挑战 → 确认 → 等牌面清空，一把做完，不来回过主循环。

        返回 False 表示确认弹窗没出来（这一下多半点空了）。
        """
        t0 = time.time()
        self._remember(self._last_cards)
        self.act(G.BTN_FORFEIT, "放弃挑战")
        t1 = time.time()
        deadline = time.time() + timeout
        while time.time() < deadline:
            if self.modal_visible():
                break
            if self.stopped():
                return False
            time.sleep(0.008)
        else:
            # 没等到弹窗就别对着桌面点「确认」：那个坐标落在牌桌上，
            # 点到什么全凭运气。让主循环重读一帧再来一次。
            self._t["forfeit"] += time.time() - t0
            self.log("   [!] 没等到放弃确认弹窗，这一下可能点空了")
            return False
        t2 = time.time()
        # 黄色按钮刚显现时命不中：等动画落位再点，否则这一下会被吞掉，
        # 只能靠主循环补救 —— 那一下要多花 1.5 秒，远不如等这 60ms。
        time.sleep(0.06)
        self.act(G.MODAL_CONFIRM, "确认放弃")
        t3 = time.time()
        self.wait_board_clear()
        t4 = time.time()
        self._last_cards = []
        self.stats.forfeits += 1
        self._t["forfeit"] += t4 - t0
        if self.verbose:
            self.log(f"   （放弃 {t4 - t0:.2f}s = 点击 {t1 - t0:.2f}"
                     f" + 等弹窗 {t2 - t1:.2f} + 确认 {t3 - t2:.2f}"
                     f" + 等清空 {t4 - t3:.2f}）")
        return True

    def round_is_one(self, tries: int = 2) -> bool:
        """放弃前的轮次复核：连续读到「1」才算数。

        第二轮里「放弃挑战」等于退出整场演算，读错一次代价太大，
        宁可多花 20ms 抓两下小框。
        """
        b = G.ROUND_NUM
        for _ in range(tries):
            patch = self.cap.grab_region(b.x1, b.y1, b.x2, b.y2)
            if self.ts.read_round_patch(patch) != 1:
                return False
            time.sleep(0.02)
        return True

    def read_round_now(self):
        """只抓轮次那个小框读数字（复核用）。"""
        b = G.ROUND_NUM
        return self.ts.read_round_patch(self.cap.grab_region(b.x1, b.y1, b.x2, b.y2))

    def drop_round(self) -> str:
        """放弃这一轮。返回 "ok" / "retry"（这一下点空了）/ "stop"（复核没过）。"""
        self._flush_pending()
        if self.ts.rounds and not self.round_is_one():
            self.log("\n[!] 轮次复核没读到「第 1 轮」：第二轮放弃会结束整场演算，已停止。")
            self.stop_reason = "round_unknown"
            return "stop"
        if self.forfeit() is False:
            self._forfeit_fail += 1
            if self._forfeit_fail >= 3:
                self.log("\n[!] 连续 3 次都没能放弃成功（点不到「放弃挑战」？），已停止。")
                self.stop_reason = "forfeit_fail"
                return "stop"
            return "retry"
        self._forfeit_fail = 0
        self._fresh_round = True
        return "ok"

    # ---------------------------------------------------------- 轮次记账
    def new_round(self) -> bool:
        """开新一轮并记一次账；返回 False 表示到了重开上限，主循环该停了。

        一轮只记一次：放弃后的第一下点击常被"重新发牌"的过场吃掉，牌桌还是空的，
        下一圈又会回到这里，靠 _armed 挡住重复计数（否则上限会按两倍地耗）。
        真正见过牌之后（主循环里那行 _armed = False）这一轮才算结束。

        """
        if self._armed:
            return True
        if self.max_attempts and self.stats.attempts >= self.max_attempts:
            return False
        self.stats.attempts += 1
        self._armed = True
        self.log(f"[第 {self.stats.attempts} 轮]")
        return True

    def _stop_max_attempts(self) -> str:
        self.log(f"\n达到重开上限 {self.max_attempts}，停止")
        self.stop_reason = "max_attempts"
        return "max_attempts"

    # ---------------------------------------------------------- 主循环
    def pace(self) -> str:
        seconds = max(0.0, time.time() - self.t0)
        n = max(1, self.stats.attempts)
        cards = self.stats.draws
        base = (f"用时 {seconds:.0f}s，{self.stats.attempts} 轮，"
                f"平均 {seconds / n:.1f}s/轮，{cards} 张牌")
        if cards:
            # 按"真翻出来的牌"算，不按点击次数 —— 冷却期点空的那些不该算战绩
            base += f"，{seconds / cards:.2f}s/张"
            if self.stats.clicks:
                base += f"（点击 {self.stats.clicks / cards:.2f} 次/张）"
            gaps = sorted(self.stats.gaps[-200:])
            if gaps:
                base += f"，出牌间隔中位 {gaps[len(gaps) // 2]:.2f}s"
        if self.stats.enemy_drops:
            base += f"，因名单弃 {self.stats.enemy_drops} 轮"
        if self.stats.mismatch:
            base += f"（表格抢跑 {self.stats.mismatch} 次，属正常）"
        return base

    def log_timing(self) -> None:
        """把各段耗时摊到每张牌上写一行：用来判断下一步该削哪一段。"""
        cards = max(1, self.stats.draws)
        rounds = max(1, self.stats.attempts)
        t = self._t
        self.log(
            f"[节奏] {self.stats.draws} 张 / {self.stats.attempts} 轮："
            f"等节拍 {t['idle'] / cards:.2f}s/张，点击 {t['click'] / cards:.2f}，"
            f"等牌 {t['card'] / cards:.2f}，读牌 {t['read'] / cards:.2f}，"
            f"放弃 {t['forfeit'] / rounds:.2f}s/轮，"
            f"点击节拍 {self.click_delay:.2f}s")

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
            if self.stats.draws:
                self.log_timing()
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
        self.log(f"抓取源：{self.cap.title}" + ("（只读模式）" if self.dry_run else ""))

        # 敌人名单：只在真的要用时才加载 25 张立绘模板
        if self.enemy.active():
            self.book = enemy_mod.Book()
            if not len(self.book):
                self.log("  [!] 敌人模板没装上（templates/enemies.json），名单不生效")
                self.enemy = enemy_mod.OFF
            else:
                self.log(f"敌人名单：{self.enemy}")
                miss = enemy_mod.unknown_names(self.enemy.names, self.book)
                if miss:
                    self.log(f"  [!] 这些名字模板库里没有，永远不会命中：{'、'.join(miss)}")

        # 坐标按实际画面尺寸缩放：换分辨率后所有 Box 都要重算
        try:
            s = self.cap.layout()
        except G.UnsupportedResolution as exc:
            self.log(f"\n[!] {exc}")
            self.stop_reason = "bad_resolution"
            return self.stop_reason
        w, h = self.cap.frame_size()
        self.log(f"画面 {w}×{h}（{screenmod.aspect(w, h)}），坐标缩放 {s:.3f}"
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
            self._mem_tick()
            if self.stats.attempts >= self._t_logged + 20:
                self._t_logged = self.stats.attempts
                self.log_timing()
            if self._fresh_round:
                # 刚放弃完：等牌面清空就直接点第一张，不再多抓一帧整屏去确认空桌
                self._fresh_round = False
                if not self.wait_board_clear(timeout=ROUND_WAIT):
                    self.log("   [!] 放弃之后牌面一直没清空，重来一次")
                    time.sleep(0.3)
                    continue
                if not self.new_round():
                    return self._stop_max_attempts()
                self._last_cards = self.draw(0)
                continue
            # 弹窗必须最先判：确认框会把背景模糊+压暗，结构自检在那里必然误判
            if self.modal_visible():
                self.log("   发现放弃确认弹窗，先确认掉")
                self.dismiss_modal()
                continue

            t_read = time.time()
            frame, cards = self.read_state(expect=self._last_cards)
            self._t["read"] += time.time() - t_read

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
            if cards and len(cards) < len(self._last_cards):
                # 手牌只会变多：变少一定是读错了（漏读一张就可能把好牌弃掉），重读一帧
                t_read = time.time()
                frame, cards = self.read_state(expect=None, tries=1)
                self._t["read"] += time.time() - t_read
            self._last_cards = cards

            # 交叉校验（只统计，不拦路）：表格高亮在换数时会滚动，比手牌慢半拍，
            # 所以对不上是常态，不能拿它当硬门禁。真出现持续错位会在结尾报出来。
            if cards:
                want = expected_highlight(sum(c.point for c in cards))
                got = find_highlight(frame)
                if want is not None and got is not None and got != want:
                    self.stats.mismatch += 1

            n = len(cards)
            stop = self.target.stop_count          # 自定义目标可能只要前几张
            left = stop - n                        # 还能再抽几张
            rnd = self.ts.read_round(frame) if self.ts.rounds else 1
            if rnd is None:
                # 淡出/翻牌动画里轮次数字可能读不出，别急着当成"第二轮"停下
                for _ in range(4):
                    time.sleep(0.03)
                    rnd = self.read_round_now()
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
            if cards and self.target.matches(cards, left):
                self._remember(cards)
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
            # 又会走到这里；new_round() 里的 _armed 保证一轮只记一次账。
            if n == 0:
                if not self.new_round():
                    return self._stop_max_attempts()
                self._last_cards = self.draw(0)
                continue

            # ---- 有牌：判断还要不要继续
            # 开局牌桌上就有牌（上一局没打完就退了，或者正在抽到一半）：这已经是
            # 第 1 轮了。不补记的话第一轮永远不记账，放弃一次之后才打出「第 1 轮」，
            # 看起来就是「第一轮的轮数显示到第二轮去了」。
            if not self.stats.attempts:
                self.new_round()
            self._armed = False          # 见到牌了，下一次空手才算新的一轮
            best = best_hand(cards)
            self.log(f"   [{n}张] {[(c.point, c.suit) for c in cards]} "
                     f"牌型={HAND_NAME_CN[best]} 战力点={sum(c.point for c in cards)}")

            # ---- 敌人名单：刚翻出来的牌命中名单就直接重开，不用等牌型判完
            banned = self._check_enemies(frame, cards)
            if banned:
                self.stats.enemy_drops += 1
                self._enemy_streak += 1
                what = "命中黑名单" if self.enemy.mode != enemy_mod.MODE_ONLY else "不在白名单"
                self.log(f"     {what}「{banned}」（立绘 {self._hit_score:.3f}），放弃重开")
                if self._enemy_streak >= enemy_mod.MAX_DROPS:
                    self.log(f"\n[!] 连着 {self._enemy_streak} 轮都撞名单，"
                             f"大概率是名单配得太宽，已停止。")
                    self.stop_reason = "enemy_ban"
                    return self.stop_reason
                if self.drop_round() == "stop":
                    return self.stop_reason
                continue

            # 战力点上限：超过就注定打不动，直接重开，别浪费时间
            if self.max_bp and sum(c.point for c in cards) > self.max_bp:
                self.log(f"     战力点 {sum(c.point for c in cards)} > 上限 {self.max_bp}，放弃")
                if self.drop_round() == "stop":
                    return self.stop_reason
                continue

            self._enemy_streak = 0        # 一轮抽满都没撞名单，连着撞的计数清零
            if left <= 0:
                if self.drop_round() == "stop":
                    return self.stop_reason
                continue

            if not self.target.possible(cards, left):
                self.log(f"     {self.target} 已不可能，提前放弃")
                if self.drop_round() == "stop":
                    return self.stop_reason
                continue

            # 把刚抽到的手牌交给下一轮 read_state 当 expect：下一帧只需抓一次整屏
            self._last_cards = self.draw(n)

    @staticmethod
    def _beep():
        try:
            import winsound
            for _ in range(3):
                winsound.Beep(900, 120)
                time.sleep(0.05)
        except Exception:
            pass


def enemy_report(frame, cards, policy) -> list:
    """读一帧时，把每个位置上认到的敌人和分数列出来（名单关着就什么都不列）。

    认准（分数 ≥ 阈值）才算认出；没认准的写"最像谁、几分"，方便判断是动画没走完
    还是模板对不上。名单命中会写"→ 弃"。
    """
    if policy is None or policy.mode == enemy_mod.MODE_OFF:
        return []
    book = enemy_mod.Book()
    if not len(book):
        return ["   [!] 敌人模板没装上（templates/enemies.json 缺了），名单不生效"]
    lines = [f"   敌人名单：{policy.label()}（阈值 {book.threshold:.2f}）"]
    if not cards:
        lines.append("   牌桌是空的，没有可认的牌")
        return lines
    for i, c in enumerate(cards):
        if c.point <= 0:
            continue
        box = enemy_mod.art_box(i)
        patch = frame[box.y1:box.y2, box.x1:box.x2]
        hit, score = book.best(patch, c.point)
        if hit is None:
            lines.append(f"   第 {i + 1} 张（{c.point} 点）：认不出")
        elif score >= book.threshold:
            if not policy.should_drop(hit.name):
                verdict = "留"
            elif policy.mode == enemy_mod.MODE_BAN:
                verdict = "弃（在名单里）"
            else:
                verdict = "弃（不在白名单里）"
            lines.append(f"   第 {i + 1} 张（{c.point} 点）：{hit.name} {score:.3f} → {verdict}")
        else:
            lines.append(f"   第 {i + 1} 张（{c.point} 点）：最像 {hit.name} {score:.3f}，"
                         f"不到阈值 → 不弃")
    return lines


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
# -*- coding: utf-8 -*-
"""主循环记账自检：python tests/test_bot.py

不开窗口、不点鼠标：把抓屏 / 模板 / 点击全部换成假的，只跑 Bot._run 的状态机，
专测「轮数」这类记账：放弃后牌桌清空、主循环靠 n == 0 记账，
一轮只该记一次，跑一小时不能变成「1 轮」或者翻倍。
"""
import os
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

from bqyw import bot as B  # noqa: E402
from bqyw.rules import SUIT_SUO, SUIT_TONG, SUIT_WAN, Card  # noqa: E402
from bqyw.targets import CustomTarget  # noqa: E402

OK = []


def check(name, got, want):
    if got != want:
        raise AssertionError(f"{name}: got {got!r}, want {want!r}")
    OK.append(name)


# 一手永远打不中「5 5 5 5 5」的牌
MISS = [Card(1, SUIT_TONG), Card(2, SUIT_SUO), Card(3, SUIT_WAN),
        Card(4, SUIT_TONG), Card(5, SUIT_SUO)]


class FakeCap:
    """假的抓屏：不碰屏幕，也不认窗口。"""

    hwnd = 0
    title = "fake"

    def layout(self):
        return 1.0

    def frame_size(self):
        return 2560, 1440

    def grab(self):
        return None

    def grab_region(self, *a, **kw):
        return None

    def wait_stable(self, timeout=0.0):
        return None

    def origin(self):
        return (0, 0)

    def status(self):
        return "fake"


class FakeTS:
    """假的模板集：牌和轮次都由测试脚本直接给。"""

    rounds = [None]
    missing = {"digit": 0, "suit": 0}

    def __init__(self, table):
        self.table = table

    def read_hand(self, frame, ox=0, oy=0):
        return list(self.table.cards)

    def read_round(self, frame):
        return 1

    def read_round_patch(self, patch, min_score=0.55):
        return 1


class Table:
    """牌桌状态机：hands 是每次铺满时给的牌，用完后一直重复最后一手。"""

    def __init__(self, hands, draws=None):
        self.cards = []
        self.hands = [list(h) for h in hands]
        self.draw_script = list(draws or [])     # 单抽时按顺序发的牌
        self.forfeits = 0
        self.draws = 0

    def next_hand(self):
        if len(self.hands) > 1:
            return self.hands.pop(0)
        return self.hands[0]

    def next_card(self):
        if self.draw_script:
            return self.draw_script.pop(0)
        return self.next_hand()[len(self.cards) % 5]


def make_bot(table, *, max_attempts=0, target="5 5 5 5 5", dry_run=True):
    logs = []
    b = B.Bot(capture=FakeCap(), target=CustomTarget.parse(target),
              dry_run=dry_run, max_attempts=max_attempts,
              verbose=False, logger=logs.append)
    b.hotkey = ""                 # 别去注册真的全局热键
    b.keep_console = True         # 别把测试的控制台最小化
    b.ts = FakeTS(table)
    b.modal_visible = lambda: False
    b.read_state = lambda expect=None, tries=4, gap=0.03: (None, list(table.cards))
    b.act = lambda box, what: None

    def fake_forfeit(timeout=1.5):
        table.forfeits += 1
        if table.forfeits > 200:
            raise AssertionError("放弃次数失控，主循环没在记账")
        table.cards = []
        return True

    b.forfeit = fake_forfeit

    def fake_draw(before, window=6.0):
        table.draws += 1
        if table.draws > 500:
            raise AssertionError("单抽次数失控")
        table.cards.append(table.next_card())
        return list(table.cards)
    b.draw = fake_draw
    return b, logs


def main():
    real_screen = B.looks_like_draw_screen
    real_find = B.find_highlight
    real_click = B.actions.click_box
    B.looks_like_draw_screen = lambda frame: True
    B.find_highlight = lambda frame: None
    try:
        # ------------------------------------------------ new_round 本身
        b, _ = make_bot(Table([MISS]), max_attempts=1)
        check("首轮记账", b.new_round(), True)
        check("首轮 attempts", b.stats.attempts, 1)
        check("同一轮不重复记账", b.new_round(), True)
        check("同一轮 attempts 不变", b.stats.attempts, 1)
        b._armed = False
        check("到上限返回 False", b.new_round(), False)
        check("到上限不记账", b.stats.attempts, 1)

        # -------------------- 跑满上限就停：一轮只记一次账，别越跑越"多"
        # 目标「5 5 5 5 5」第一张就凑不上，会走「提前放弃」那条路：
        # 放弃后牌桌清空，主循环靠 n == 0 记账，同样一轮只该记一次。
        t3 = Table([MISS])
        b3, logs3 = make_bot(t3, max_attempts=3)
        check("普通模式 停因", b3._run(), "max_attempts")
        check("普通模式 每轮都打轮数", len([x for x in logs3 if "轮]" in x]), 3)
        check("普通模式 轮数", b3.stats.attempts, 3)
        check("普通模式 提前放弃后每轮只抽 1 张", t3.draws, 3)
        check("普通模式 放弃次数", t3.forfeits, 3)

        # 普通模式铺满 5 张：抽牌次数按张算，命中那一轮也进总数
        t5 = Table([MISS])
        b5, _ = make_bot(t5, target="任意 任意 任意 任意 任意")
        check("普通模式 命中停因", b5._run(), "hit")
        check("普通模式 命中轮数", b5.stats.attempts, 1)
        check("普通模式 命中抽牌数", t5.draws, 5)
        check("普通模式 命中摘要", "1 轮" in b5.pace(), True)

        # -------------- 开局牌桌上就有牌：那已经是第 1 轮，不能等放弃后才记
        t7 = Table([MISS])
        t7.cards = [Card(5, SUIT_SUO)]      # 上一局没打完，桌上还剩 1 张
        b7, logs7 = make_bot(t7, max_attempts=2)
        check("开局有牌 停因", b7._run(), "max_attempts")
        check("开局有牌 轮数", b7.stats.attempts, 2)
        marks = [x for x in logs7 if "轮]" in x]
        check("开局有牌 轮数行数", len(marks), 2)
        check("开局有牌 第一行就是第 1 轮", marks[0].endswith("[第 1 轮]"), True)

        # ------------------------------------------------ 节拍器：点了就出牌就收工
        t8 = Table([MISS])
        b8 = B.Bot(capture=FakeCap(), target=CustomTarget.parse("5 5 5 5 5"),
                   dry_run=False, verbose=False, logger=lambda s: None)
        b8.hotkey = ""
        b8.ts = FakeTS(t8)
        b8.act = lambda box, what: t8.cards.append(t8.next_card())
        got = b8.draw(0)
        check("节拍器 出牌张数", len(got), 1)
        check("节拍器 记账按牌算", b8.stats.draws, 1)
        check("节拍器 只点一次", b8.stats.clicks, 1)

        # -------------------------- 冷却模拟：点是点空的，但节拍器必须自己补回来
        class CooldownTable(Table):
            def __init__(self, hands):
                super().__init__(hands)
                self.last_accept = 0.0
                self.accepted = 0

            def click(self, cooldown=1.1):
                if time.time() - self.last_accept < cooldown:
                    return                      # 冷却期：游戏直接丢掉
                self.last_accept = time.time()
                self.accepted += 1
                self.cards.append(self.next_card())

        t11 = CooldownTable([MISS])
        b11 = B.Bot(capture=FakeCap(), target=CustomTarget.parse("5 5 5 5 5"),
                    dry_run=False, verbose=False, logger=lambda s: None)
        b11.hotkey = ""
        b11.ts = FakeTS(t11)
        b11.act = lambda box, what: t11.click()
        t0 = time.time()
        got = b11.draw(0)                       # 第一下就该成
        check("冷却 第一张出牌", len(got), 1)
        got2 = b11.draw(1)                      # 第二下必然撞冷却，靠节拍器补
        used = time.time() - t0
        check("冷却 第二张也出牌", len(got2), 2)
        check("冷却 补点后只成功一次", t11.accepted, 2)
        check("冷却 没死等满 6 秒", used < 3.0, True)

        # -------------------- 冷却比节拍长：点空一次要把节拍调大，且下一张照样出得来
        t12 = CooldownTable([MISS])
        b12 = B.Bot(capture=FakeCap(), target=CustomTarget.parse("5 5 5 5 5"),
                    dry_run=False, verbose=False, logger=lambda s: None)
        b12.hotkey = ""
        b12.ts = FakeTS(t12)
        b12.act = lambda box, what: t12.click()
        t12.last_accept = time.time()          # 假装 0.1 秒前刚点过，这一下必被吃
        cards = b12.draw(0)
        check("冷却更长 仍出牌", len(cards), 1)
        # 起始 1.45，点空 +0.15 → 1.60，出牌后回落 0.01 → 1.59
        check("冷却更长 节拍自动调大", round(b12.click_delay, 2), 1.59)
        check("冷却更长 只点了两次", b12.stats.clicks, 2)

        # ------------------------------------------------ 放弃前的轮次复核
        class BadRound(FakeTS):
            def read_round_patch(self, patch, min_score=0.55):
                return 2

        t9 = Table([MISS])
        b9, _ = make_bot(t9, max_attempts=1)
        b9.ts = BadRound(t9)
        check("轮次复核没过 不放弃", b9.drop_round(), "stop")
        check("轮次复核没过 停因", b9.stop_reason, "round_unknown")
        check("轮次复核没过 真没放弃", t9.forfeits, 0)

        # 放弃点空了应该重试，而不是直接算放弃成功
        t10 = Table([MISS])
        b10, _ = make_bot(t10, max_attempts=1)
        b10.forfeit = lambda timeout=1.5, wait_clear=True: False
        check("放弃没点到 先重试", b10.drop_round(), "retry")
        b10.drop_round()
        check("连续点空 第 3 次停手", b10.drop_round(), "stop")
        check("连续点空 停因", b10.stop_reason, "forfeit_fail")

    finally:
        B.looks_like_draw_screen = real_screen
        B.find_highlight = real_find
        B.actions.click_box = real_click

    print(f"{len(OK)} 项检查全部通过")
    for name in OK:
        print("  ok  " + name)
    return 0


if __name__ == "__main__":
    sys.exit(main())

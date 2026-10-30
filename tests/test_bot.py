# -*- coding: utf-8 -*-
"""主循环记账自检：python tests/test_bot.py

不开窗口、不点鼠标：把抓屏 / 模板 / 点击全部换成假的，只跑 Bot._run 的状态机，
专测「轮数」这类记账。快速模式曾经在这里出过 bug —— 放弃后立刻连点，新牌在
下一帧就铺满了，主循环再也等不到空牌桌，于是跑了一小时还显示「1 轮」。
"""
import os
import sys

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
HIT = [Card(5, SUIT_SUO)] * 5


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

    def read_hand(self, frame):
        return list(self.table.cards)

    def read_round(self, frame):
        return 1


class Table:
    """牌桌状态机：hands 是每次铺满时给的牌，用完后一直重复最后一手。"""

    def __init__(self, hands, draws=None):
        self.cards = []
        self.hands = [list(h) for h in hands]
        self.draw_script = list(draws or [])     # 单抽时按顺序发的牌
        self.forfeits = 0
        self.bursts = 0
        self.draws = 0

    def next_hand(self):
        if len(self.hands) > 1:
            return self.hands.pop(0)
        return self.hands[0]

    def next_card(self):
        if self.draw_script:
            return self.draw_script.pop(0)
        return self.next_hand()[len(self.cards) % 5]


def make_bot(table, *, fast=True, max_attempts=0, stub_burst=True,
             target="5 5 5 5 5", dry_run=True):
    logs = []
    b = B.Bot(capture=FakeCap(), target=CustomTarget.parse(target),
              fast_mode=fast, dry_run=dry_run, max_attempts=max_attempts,
              verbose=False, logger=logs.append)
    b.hotkey = ""                 # 别去注册真的全局热键
    b.keep_console = True         # 别把测试的控制台最小化
    b.ts = FakeTS(table)
    b.modal_visible = lambda: False
    b.read_state = lambda expect=None, tries=4, gap=0.03: (None, list(table.cards))
    b.act = lambda box, what: None

    def fake_forfeit(timeout=2.0, wait_clear=True):
        table.forfeits += 1
        if table.forfeits > 200:
            raise AssertionError("放弃次数失控，主循环没在记账")
        table.cards = []

    b.forfeit = fake_forfeit

    if stub_burst:
        def fake_burst(seconds=6.0, want=5):
            table.bursts += 1
            if table.bursts > 200:
                raise AssertionError("连点次数失控")
            table.cards = list(table.next_hand())
            return list(table.cards)
        b.draw_burst = fake_burst

    def fake_draw(before, timeout=2.5):
        table.draws += 1
        if table.draws > 500:
            raise AssertionError("单抽次数失控")
        table.cards.append(table.next_card())
        return len(table.cards)
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

        # -------------------------------- 快速模式：跑满上限就停（曾经停不下来）
        t1 = Table([MISS])
        b1, logs1 = make_bot(t1, max_attempts=3)
        reason = b1._run()
        check("快速模式 停因", reason, "max_attempts")
        check("快速模式 轮数", b1.stats.attempts, 3)
        check("快速模式 每轮都打轮数", len([x for x in logs1 if "轮]" in x]), 3)
        check("快速模式 连点次数", t1.bursts, 3)
        check("快速模式 放弃次数", t1.forfeits, 3)
        check("快速模式 摘要里的轮数", "3 轮" in b1.pace(), True)

        # ------------------------------------------ 快速模式：命中那一轮也要算
        t2 = Table([MISS, MISS, HIT])
        b2, _ = make_bot(t2)
        check("快速模式 命中停因", b2._run(), "hit")
        check("快速模式 命中轮数", b2.stats.attempts, 3)
        check("快速模式 命中放弃次数", t2.forfeits, 2)

        # -------------------------------------- 普通模式不能被这次改动带坏
        # 目标「5 5 5 5 5」第一张就凑不上，会走「提前放弃」那条路：
        # 放弃后牌桌清空，主循环靠 n == 0 记账，同样一轮只该记一次。
        t3 = Table([MISS])
        b3, logs3 = make_bot(t3, fast=False, max_attempts=3)
        check("普通模式 停因", b3._run(), "max_attempts")
        check("普通模式 每轮都打轮数", len([x for x in logs3 if "轮]" in x]), 3)
        check("普通模式 轮数", b3.stats.attempts, 3)
        check("普通模式 提前放弃后每轮只抽 1 张", t3.draws, 3)
        check("普通模式 放弃次数", t3.forfeits, 3)

        # 普通模式铺满 5 张：抽牌次数按张算，命中那一轮也进总数
        t5 = Table([MISS])
        b5, _ = make_bot(t5, fast=False, target="任意 任意 任意 任意 任意")
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

        # ---------------- 快速模式 + 只要前几张的目标：绝不能连点，会一直中不了
        t6 = Table([MISS], draws=[Card(5, SUIT_TONG), Card(5, SUIT_SUO),
                                  Card(3, SUIT_WAN)] * 4)
        b6, _ = make_bot(t6, fast=True, max_attempts=2, target="5 5 5")
        check("快速模式三张 停因", b6._run(), "max_attempts")
        check("快速模式三张 轮数", b6.stats.attempts, 2)
        check("快速模式三张 绝不连点", t6.bursts, 0)
        check("快速模式三张 每轮 3 张", t6.draws, 6)
        check("快速模式三张 放弃次数", t6.forfeits, 2)

        # ------------------------ 连点记账按真翻出的牌算，不按被吃掉的点击次数
        t4 = Table([MISS])
        t4.cards = list(MISS)
        b4, _ = make_bot(t4, stub_burst=False, dry_run=False)
        b4.board_count = lambda: len(t4.cards)
        clicks = []
        B.actions.click_box = lambda box, origin=None: clicks.append(1)
        try:
            cards = b4.draw_burst(0.03, 5)
        finally:
            B.actions.click_box = real_click
        check("连点返回手牌", len(cards), 5)
        check("连点记账=真牌数", b4.stats.draws, 5)
        check("连点确实点了很多下", len(clicks) > 5, True)
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

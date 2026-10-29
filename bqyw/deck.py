# -*- coding: utf-8 -*-
"""牌堆模型与「目标牌型还能不能达成」的精确概率。

牌堆（官方界面右侧「铭牌库剩余」实测）：战力点 1~5 各 6/6/6/6/10 张，共 34 张。
花色在点数内的分配界面**不显示**，这里取均匀假设：
    点数 1~4 → 筒 2 / 索 2 / 萬 2
    点数 5   → 筒 4 / 索 3 / 萬 3
（与社区统计的 筒18/万14/索11 相差不大；只影响同花/同花顺，占比不到 2%。）

核心能力：
    completion_probability(drawn, target)  —— 给定已抽的牌，继续抽下去能凑出 target 的概率
    0 就代表「已经没戏了」，这正是自动重开时要用的剪枝信号。
"""
from __future__ import annotations

from functools import lru_cache
from typing import Iterable

from .rules import SUIT_TONG, SUIT_SUO, SUIT_WAN, Card, Hand, all_hands

MAX_DRAWS = 5

DECK_BY_POINT = {1: 6, 2: 6, 3: 6, 4: 6, 5: 10}
DECK_TOTAL = sum(DECK_BY_POINT.values())        # 34

# (战力点, 花色) -> 张数
SUIT_SPLIT = {
    1: {SUIT_TONG: 2, SUIT_SUO: 2, SUIT_WAN: 2},
    2: {SUIT_TONG: 2, SUIT_SUO: 2, SUIT_WAN: 2},
    3: {SUIT_TONG: 2, SUIT_SUO: 2, SUIT_WAN: 2},
    4: {SUIT_TONG: 2, SUIT_SUO: 2, SUIT_WAN: 2},
    5: {SUIT_TONG: 4, SUIT_SUO: 3, SUIT_WAN: 3},
}

# 固定的降压顺序，保证状态可哈希
CELLS: tuple[tuple[int, str], ...] = tuple(
    (p, s) for p in sorted(DECK_BY_POINT) for s in (SUIT_TONG, SUIT_SUO, SUIT_WAN)
)


def full_deck() -> tuple[int, ...]:
    """初始牌堆，与 CELLS 同序的张数向量。"""
    return tuple(SUIT_SPLIT[p][s] for p, s in CELLS)


def as_vector(cards: Iterable[Card] = ()) -> tuple[int, ...]:
    """把已抽的牌转成「还剩多少」向量。花色未知（'?'）时按点数均摊扣减。"""
    left = list(full_deck())
    for c in cards:
        idx = [i for i, (p, s) in enumerate(CELLS) if p == c.point]
        if c.suit != "?":
            hit = [i for i in idx if CELLS[i][1] == c.suit]
            if hit and left[hit[0]] > 0:
                left[hit[0]] -= 1
                continue
        # 花色未知或已扣完：挑剩余最多的那个花色扣
        best = max(idx, key=lambda i: left[i])
        if left[best] > 0:
            left[best] -= 1
    return tuple(left)


def _drawn_from(left: tuple[int, ...]) -> list[Card]:
    full = full_deck()
    out: list[Card] = []
    for i, (p, s) in enumerate(CELLS):
        for _ in range(full[i] - left[i]):
            out.append(Card(p, s))
    return out


def completion_probability(
    drawn: Iterable[Card] = (),
    target: Hand = Hand.QUADS,
    draws_left: int = MAX_DRAWS,
    left: tuple[int, ...] | None = None,
) -> float:
    """在 draws_left 次抽取内凑出 target 的概率（0 表示已不可能）。"""
    if left is None:
        left = as_vector(drawn)

    @lru_cache(maxsize=None)
    def f(state: tuple[int, ...], depth: int) -> float:
        cur = _drawn_from(state)
        if target in all_hands(cur):
            return 1.0
        if depth == 0:
            return 0.0
        total = sum(state)
        if total == 0:
            return 0.0
        acc = 0.0
        for i, n in enumerate(state):
            if n:
                nxt = list(state)
                nxt[i] -= 1
                acc += (n / total) * f(tuple(nxt), depth - 1)
        return acc

    return f(left, draws_left)


def completion_probability_pred(check, draws_left: int = MAX_DRAWS,
                               left: tuple[int, ...] | None = None) -> float:
    """同上，但用一个随便的判定函数（自定义目标用）。"""
    if left is None:
        left = full_deck()

    @lru_cache(maxsize=None)
    def f(state: tuple[int, ...], depth: int) -> float:
        cur = _drawn_from(state)
        if check(cur):
            return 1.0
        if depth == 0:
            return 0.0
        total = sum(state)
        if total == 0:
            return 0.0
        acc = 0.0
        for i, n in enumerate(state):
            if n:
                nxt = list(state)
                nxt[i] -= 1
                acc += (n / total) * f(tuple(nxt), depth - 1)
        return acc

    return f(left, draws_left)


def expected_attempts(target: Hand, per_run_draws: int = MAX_DRAWS) -> float:
    """从空手开始，平均要重开多少次才能凑出目标。"""
    p = completion_probability((), target, per_run_draws, full_deck())
    return 1.0 / p if p > 0 else float("inf")


def reachable(drawn: Iterable[Card], target: Hand, draws_left: int) -> bool:
    """还不来不来得及 —— 自动抽牌的剪枝开关。"""
    return completion_probability(drawn, target, draws_left) > 0.0


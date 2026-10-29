# -*- coding: utf-8 -*-
"""牌型判定与计分。

口径来自游戏内「组合说明」页（截图 refs/组合说明-其他.jpg）：

    同时达成多种好运组合时，优先生效基础增益倍率更高的组合。

倍率：散牌 1 · 对子 1.2 · 两对 1.4 · 三条 1.6 · 顺子 1.8
      葫芦 2 · 同花 4 · 四条 6 · 同花顺 8

判定细则（与官方文案逐字对齐）：
  * 顺子      —— 「抽出 4 张战力点连续的铭牌」，**4 张即可**，多余的牌不影响
  * 同花      —— 「抽出的所有铭牌同花色（至少抽取 4 张）」，
                 即**手上每一张都必须同色**，第 5 张异色会直接把同花弄丢
  * 同花顺    —— 「抽出 5 张战力点连续且同花色的铭牌」，只可能是 1-2-3-4-5
  * 五条      —— 游戏没有这个档位，按四条算
"""
from __future__ import annotations

from collections import Counter
from dataclasses import dataclass
from enum import IntEnum

# ---------------------------------------------------------------- 花色 / 牌

SUIT_TONG = "筒"
SUIT_SUO = "索"
SUIT_WAN = "萬"
SUITS = (SUIT_TONG, SUIT_SUO, SUIT_WAN)
SUIT_UNKNOWN = "?"

# 目标里的「任意点数」：只有 CustomTarget 会用到，抽出来的真牌永远是 1..5
POINT_ANY = 0


@dataclass(frozen=True, order=True)
class Card:
    point: int                    # 战力点 1..5；0 = 任意（仅用于目标描述）
    suit: str = SUIT_UNKNOWN      # 花色；暂不认识时用 '?'

    def __post_init__(self):
        if not 0 <= self.point <= 5:
            raise ValueError(f"战力点必须在 0..5，收到 {self.point}")


# ---------------------------------------------------------------- 牌型

class Hand(IntEnum):
    """按基础增益倍率从低到高排序，值越大越优先。"""

    HIGH = 1            # 散牌
    PAIR = 2            # 对子
    TWO_PAIR = 3        # 两对
    TRIPS = 4           # 三条
    STRAIGHT = 5        # 顺子
    FULL_HOUSE = 6      # 葫芦
    FLUSH = 7           # 同花
    QUADS = 8           # 四条
    STRAIGHT_FLUSH = 9  # 同花顺


HAND_NAME_CN = {
    Hand.HIGH: "散牌",
    Hand.PAIR: "对子",
    Hand.TWO_PAIR: "两对",
    Hand.TRIPS: "三条",
    Hand.STRAIGHT: "顺子",
    Hand.FULL_HOUSE: "葫芦",
    Hand.FLUSH: "同花",
    Hand.QUADS: "四条",
    Hand.STRAIGHT_FLUSH: "同花顺",
}

MULT = {
    Hand.HIGH: 1.0,
    Hand.PAIR: 1.2,
    Hand.TWO_PAIR: 1.4,
    Hand.TRIPS: 1.6,
    Hand.STRAIGHT: 1.8,
    Hand.FULL_HOUSE: 2.0,
    Hand.FLUSH: 4.0,
    Hand.QUADS: 6.0,
    Hand.STRAIGHT_FLUSH: 8.0,
}

# 战力点 -> 奖励点（底分），官方表格 refs/战力点1~10.png 与 refs/战力点10~20.png
BASE = {
    1: 1_000, 2: 3_000, 3: 6_000, 4: 10_000, 5: 15_000,
    6: 20_000, 7: 25_000, 8: 30_000, 9: 35_000, 10: 40_000,
    11: 50_000, 12: 60_000, 13: 70_000, 14: 80_000, 15: 90_000,
    16: 100_000, 17: 110_000, 18: 120_000, 19: 130_000, 20: 150_000,
}
CAP_BP = 20          # 战力点上限（表格 MAX）
OVERFLOW_BP = 10     # 超过该值触发数据溢出


# ---------------------------------------------------------------- 判定

def _runs(points) -> int:
    """去重后最长连号长度。"""
    u = sorted(set(points))
    if not u:
        return 0
    best = cur = 1
    for a, b in zip(u, u[1:]):
        cur = cur + 1 if b == a + 1 else 1
        best = max(best, cur)
    return best


def all_hands(cards) -> set[Hand]:
    """返回手牌同时满足的全部牌型（不做取舍）。"""
    if not cards:
        return {Hand.HIGH}

    pts = [c.point for c in cards]
    cnt = Counter(pts)
    reps = sorted(cnt.values(), reverse=True)
    n = len(cards)
    out: set[Hand] = {Hand.HIGH}

    quads = 4 in reps or 5 in reps
    trips = 3 in reps
    pairs = reps.count(2)

    if quads:
        out.add(Hand.QUADS)
    if trips and pairs >= 1:
        out.add(Hand.FULL_HOUSE)
    if trips:
        out.add(Hand.TRIPS)
    if pairs >= 2:
        out.add(Hand.TWO_PAIR)
    elif pairs == 1:
        out.add(Hand.PAIR)

    if _runs(pts) >= 4:
        out.add(Hand.STRAIGHT)

    # 同花：所有牌同色，且至少 4 张；花色未知时无法判定
    if n >= 4 and all(c.suit == cards[0].suit for c in cards) and cards[0].suit != SUIT_UNKNOWN:
        out.add(Hand.FLUSH)
        if n >= 5 and _runs(pts) >= 5:
            out.add(Hand.STRAIGHT_FLUSH)

    return out


def best_hand(cards) -> Hand:
    """按基础倍率取最高的那一个（游戏规则：优先生效基础倍率更高的）。"""
    return max(all_hands(cards), key=lambda h: MULT[h])


def hand_value(cards, lucky: Hand | None = None) -> tuple[Hand, float]:
    """返回 (生效牌型, 生效倍率)。lucky 命中时该倍率 ×2。"""
    h = best_hand(cards)
    mult = MULT[h]
    if lucky is not None and h is lucky:
        mult *= 2
    return h, mult


def battle_points(cards) -> int:
    """战力点合计，超过 20 截断。"""
    return min(sum(c.point for c in cards), CAP_BP)


ROUND2_UNIT = 100_000


def round_multiplier(s1: int) -> float:
    """第二轮底分增幅系数 K = S1 / 10w + 1。

    这条是**实测确认**的，不是拟合：截图里第一轮 60w，第二轮表格的底分
    恰好是第一轮表格的 7 倍（10 点 4w→28w，17 点 11w→77w，MAX 20 15w→105w），
    即 K = 600000/100000 + 1 = 7。
    """
    return 1.0 + s1 / ROUND2_UNIT


def base_reward(cards, s1: int = 0) -> float:
    """底分（第二轮会按 K 放大）。"""
    return BASE[battle_points(cards)] * round_multiplier(s1)


def score(cards, lucky: Hand | None = None, s1: int = 0) -> int:
    """本局奖励点 = 底分 × 倍率；s1 为第一轮已获得的奖励点（第二轮传进来）。"""
    _, mult = hand_value(cards, lucky)
    return int(round(base_reward(cards, s1) * mult))


def battle_total(s1: int, cards, lucky: Hand | None = None) -> int:
    """两轮合计 = S1 + S2。"""
    return s1 + score(cards, lucky, s1)


def is_overflow(cards) -> bool:
    return sum(c.point for c in cards) > OVERFLOW_BP

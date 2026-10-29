# -*- coding: utf-8 -*-
"""抽牌目标：可以是 8 种标准牌型之一，也可以是「指定的 5 张牌」。

自定义目标两种口径：
  * 顺序无关（默认）—— 最终这 5 张和指定的一模一样即可，先后顺序不管
  * 按顺序        —— 第 1 张必须是指定的第 1 张，第 2 张必须是指定的第 2 张……

点数写「任意」表示不挑点数，花色写「任意」表示不挑花色，两个都能单独用。
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from functools import lru_cache

from .deck import MAX_DRAWS, reachable
from .rules import (HAND_NAME_CN, POINT_ANY, SUITS, SUIT_UNKNOWN, Card, Hand,
                    all_hands)

ANY = "任意"
TOKEN_RE = re.compile(r"^(任意|[1-5])(.*)$")


class TargetBase:
    label = ""
    stop_count = MAX_DRAWS      # 抽到第几张停手；标准牌型一律按 5 张算

    def matches(self, cards: list[Card]) -> bool:
        raise NotImplementedError

    def possible(self, cards: list[Card], draws_left: int) -> bool:
        raise NotImplementedError

    def __str__(self) -> str:
        return self.label


class HandTarget(TargetBase):
    """标准牌型目标。"""

    def __init__(self, hand: Hand):
        self.hand = hand
        self.label = HAND_NAME_CN[hand]

    def matches(self, cards: list[Card]) -> bool:
        return bool(cards) and self.hand in all_hands(cards)

    def possible(self, cards: list[Card], draws_left: int) -> bool:
        return reachable(cards, self.hand, draws_left)

    def __eq__(self, other):
        return isinstance(other, HandTarget) and other.hand == self.hand

    def __hash__(self):
        return hash(("hand", int(self.hand)))


@dataclass(frozen=True)
class CustomTarget(TargetBase):
    """指定的 5 张牌。"""

    cards: tuple[Card, ...]      # 1~5 张；point=POINT_ANY / suit=SUIT_UNKNOWN 表示不挑
    ordered: bool = False

    def __post_init__(self):
        if not 1 <= len(self.cards) <= MAX_DRAWS:
            raise ValueError(f"自定义目标要 1~{MAX_DRAWS} 张，现在是 {len(self.cards)} 张")

    @property
    def stop_count(self) -> int:
        """抽到第几张就停手。"""
        return len(self.cards)

    # ---------------------------------------------------------- 内部
    @staticmethod
    def _one(card: Card, want: Card) -> bool:
        if want.point != POINT_ANY and card.point != want.point:
            return False
        return want.suit == SUIT_UNKNOWN or card.suit == want.suit

    @classmethod
    def _fit(cls, want, cards: list[Card]) -> bool:
        """把抽到的牌一一对到目标位上（回溯）。

        不能用贪心：目标是「任意筒 + 2筒」、抽到「2筒 + 3筒」时，贪心会把第一张
        2筒派给「任意筒」，然后 3筒 无处可去，其实换个分法就成了。
        """
        if not cards:
            return True
        first, rest = cards[0], cards[1:]
        for i, w in enumerate(want):
            if cls._one(first, w) and cls._fit(want[:i] + want[i + 1:], rest):
                return True
        return False

    @staticmethod
    def _fmt(card: Card) -> str:
        point = "任意" if card.point == POINT_ANY else str(card.point)
        return f"{point}{'?' if card.suit == SUIT_UNKNOWN else card.suit}"

    @property
    def label(self) -> str:
        body = " ".join(self._fmt(c) for c in self.cards)
        return f"自定义({body}{'，按顺序' if self.ordered else ''})"

    # ---------------------------------------------------------- 判定
    def matches(self, cards: list[Card]) -> bool:
        if len(cards) != len(self.cards):
            return False
        if self.ordered:
            return all(self._one(c, w) for c, w in zip(cards, self.cards))
        return self._fit(list(self.cards), list(cards))

    def possible(self, cards: list[Card], draws_left: int) -> bool:
        if len(cards) > len(self.cards):
            return False
        if self.ordered:
            return all(self._one(c, w) for c, w in zip(cards, self.cards))
        return self._fit(list(self.cards), list(cards))

    # ---------------------------------------------------------- 解析
    @classmethod
    def parse(cls, spec: str, ordered: bool = False) -> "CustomTarget":
        """'3筒 3筒 3索 5萬 5' -> CustomTarget。

        点数写「任意」表示不挑（可带花色，如「任意筒」），没写花色的当任意花色。
        """
        if not spec or not spec.strip():
            raise ValueError("自定义目标不能为空")
        tokens = [t for t in re.split(r"[,，\s]+", spec.strip()) if t]
        # 「不抽」及其后的内容一律忽略，用来表示这一局只抽到前面几张
        for i, tok in enumerate(tokens):
            if tok in ("不抽", "停", "-", "x", "X"):
                tokens = tokens[:i]
                break
        if not 1 <= len(tokens) <= MAX_DRAWS:
            raise ValueError(f"要写 1~{MAX_DRAWS} 张，现在写了 {len(tokens)} 张：{tokens}")
        out: list[Card] = []
        for tok in tokens:
            m = TOKEN_RE.match(tok)
            if not m:
                raise ValueError(
                    f"看不懂这张牌：{tok!r}（写成 3筒 / 3萬 / 任意 / 任意筒 这样）")
            head = m.group(1)
            point = POINT_ANY if head == "任意" else int(head)
            suit_part = m.group(2).strip()
            if not suit_part or suit_part in ("?", "*", ANY, "任意"):
                suit = SUIT_UNKNOWN
            elif suit_part in SUITS:
                suit = suit_part
            elif suit_part in ("万", "萬"):
                suit = "萬"
            else:
                raise ValueError(f"不认识的花色：{suit_part!r}（可选 筒/索/萬/任意）")
            out.append(Card(point, suit))
        return cls(tuple(out), ordered)


def _ordered_probability(target: "CustomTarget") -> float:
    """按顺序达成的概率。

    不能走 completion_probability_pred：那边是拿「剩余牌堆」重建手牌的，
    顺序信息已经丢了。这里按位置递推：第 k 位抽到某格的概率 = 该格剩余 / 剩余总张数。
    """
    from .deck import DECK_TOTAL, SUIT_SPLIT

    cells = [(p, s, n) for p, d in sorted(SUIT_SPLIT.items()) for s, n in d.items()]
    index = {(p, s): i for i, (p, s, _) in enumerate(cells)}
    stock = tuple(n for _, _, n in cells)

    allowed: list[list[int]] = []
    for c in target.cards:
        points = range(1, 6) if c.point == POINT_ANY else (c.point,)
        suits = SUITS if c.suit == SUIT_UNKNOWN else (c.suit,)
        allowed.append([index[(p, s)] for p in points for s in suits])

    @lru_cache(maxsize=None)
    def f(used: tuple[int, ...], k: int) -> float:
        if k == len(allowed):
            return 1.0
        total = DECK_TOTAL - k
        acc = 0.0
        for i in allowed[k]:
            avail = stock[i] - used[i]
            if avail > 0:
                nxt = list(used)
                nxt[i] += 1
                acc += (avail / total) * f(tuple(nxt), k + 1)
        return acc

    return f((0,) * len(stock), 0)


def estimate_attempts(target: TargetBase) -> float:
    """从空手开始，平均要重开多少次才能达成目标。"""
    from .deck import completion_probability_pred, expected_attempts as _hand_exp

    if isinstance(target, HandTarget):
        return _hand_exp(target.hand)
    if target.ordered:
        p = _ordered_probability(target)
    else:
        p = completion_probability_pred(lambda cards: target.matches(cards))
    return 1.0 / p if p > 0 else float("inf")


def make_target(kind: str, spec: str | None = None, ordered: bool = False) -> TargetBase:
    """按界面/命令行给的名字造一个目标。kind 可以是牌型名，也可以是「自定义」。"""
    if kind == "自定义":
        if not spec:
            raise ValueError("选了自定义却没给牌面")
        return CustomTarget.parse(spec, ordered)
    for h, name in HAND_NAME_CN.items():
        if name == kind:
            return HandTarget(h)
    raise ValueError(f"未知目标：{kind!r}")

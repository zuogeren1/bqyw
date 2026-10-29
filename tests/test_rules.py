# -*- coding: utf-8 -*-
"""规则自检：python tests/test_rules.py"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from bqyw.rules import (  # noqa: E402
    BASE, CAP_BP, MULT, Card, Hand, all_hands, base_reward, battle_points,
    battle_total, best_hand, round_multiplier, score,
)

C = Card
OK = []


def check(name, got, want):
    if got != want:
        raise AssertionError(f"{name}: got {got!r}, want {want!r}")
    OK.append(name)


def cards(*pts, suit="?"):
    return [C(p, suit) for p in pts]


# ---------------- 基础牌型
check("散牌", best_hand(cards(1, 3, 5)), Hand.HIGH)
check("对子", best_hand(cards(1, 1, 3)), Hand.PAIR)
check("两对", best_hand(cards(1, 1, 2, 2)), Hand.TWO_PAIR)
check("三条", best_hand(cards(1, 1, 1, 3)), Hand.TRIPS)
check("四条", best_hand(cards(1, 1, 1, 1)), Hand.QUADS)
check("五条算四条", best_hand(cards(1, 1, 1, 1, 1)), Hand.QUADS)
check("葫芦", best_hand(cards(2, 2, 2, 3, 3)), Hand.FULL_HOUSE)
check("葫芦压三条", all_hands(cards(2, 2, 2, 3, 3)), {Hand.HIGH, Hand.TRIPS, Hand.PAIR, Hand.FULL_HOUSE})

# ---------------- 顺子：4 张成立，多余牌不影响
check("顺子4张", best_hand(cards(1, 2, 3, 4)), Hand.STRAIGHT)
check("顺子5张", best_hand(cards(1, 2, 3, 4, 5)), Hand.STRAIGHT)
check("顺子带重复", best_hand(cards(1, 2, 3, 4, 4)), Hand.STRAIGHT)
check("顺子带散张", best_hand(cards(1, 2, 3, 4, 2)), Hand.STRAIGHT)
check("2-5 顺子", best_hand(cards(2, 3, 4, 5)), Hand.STRAIGHT)
check("三条不构成顺子", best_hand(cards(1, 1, 1, 2, 3)), Hand.TRIPS)

# ---------------- 同花 / 同花顺
T, S = "筒", "索"
check("同花4张", best_hand([C(1, T), C(3, T), C(5, T), C(2, T)]), Hand.FLUSH)
check("同花少于4张不成立", best_hand([C(1, T), C(3, T), C(5, T)]), Hand.HIGH)
check("第5张异色即丢失同花",
      Hand.FLUSH in all_hands([C(1, T), C(3, T), C(5, T), C(2, T), C(4, S)]), False)
check("第5张同色仍是同花",
      Hand.FLUSH in all_hands([C(1, T), C(3, T), C(5, T), C(2, T), C(4, T)]), True)
check("同花顺", best_hand([C(p, T) for p in (1, 2, 3, 4, 5)]), Hand.STRAIGHT_FLUSH)
check("同花不成顺", best_hand([C(p, T) for p in (1, 2, 3, 4, 4)]), Hand.FLUSH)
check("顺子非同花", best_hand([C(1, T), C(2, S), C(3, "萬"), C(4, T)]), Hand.STRAIGHT)

# ---------------- 取基础倍率最高
check("四条+顺子", best_hand(cards(1, 2, 3, 4, 4)), Hand.STRAIGHT)
check("同花压顺子", best_hand([C(1, T), C(2, T), C(3, T), C(4, T)]), Hand.FLUSH)

# ---------------- 表
check("倍率表", {h.name: MULT[h] for h in Hand}, {
    "HIGH": 1.0, "PAIR": 1.2, "TWO_PAIR": 1.4, "TRIPS": 1.6, "STRAIGHT": 1.8,
    "FULL_HOUSE": 2.0, "FLUSH": 4.0, "QUADS": 6.0, "STRAIGHT_FLUSH": 8.0,
})
check("底分", [BASE[1], BASE[5], BASE[10], BASE[19], BASE[20]],
      [1000, 15000, 40000, 130000, 150000])

# ---------------- 战力点 / 计分
check("战力点合计", battle_points(cards(1, 2, 3)), 6)
check("战力点截断", battle_points(cards(5, 5, 5, 5, 5)), CAP_BP)
check("对子5点", score(cards(1, 1, 3)), 18000)                       # 15000 × 1.2
check("幸运对子", score(cards(1, 1, 3), lucky=Hand.PAIR), 36000)      # ×2
check("四条20点", score(cards(5, 5, 5, 5)), 900000)                   # 150000 × 6
check("同花顺", score([C(p, T) for p in (1, 2, 3, 4, 5)]), 720000)    # 战力点15 = 90000 × 8
check("散牌", score(cards(1, 3)), 10000)                             # 底分4 = 10000

# ---------------- 第二轮：用实机截图 refs/抽牌界面-第二轮-5张.png 对账
# 手牌 3筒 4索 1萬 5索 4筒 → 战力点 17、对子；第一轮 S1 = 600000
R2 = [C(3, T), C(4, S), C(1, "萬"), C(5, S), C(4, T)]
check("轮次系数", round_multiplier(600000), 7.0)
check("第二轮底分", base_reward(R2, 600000), 770000)                  # 11w × 7
check("第二轮得分", score(R2, lucky=Hand.PAIR, s1=600000), 1848000)   # 77w × 2.4
check("两轮合计", battle_total(600000, R2, Hand.PAIR), 2448000)       # 截图里的 2448000

print(f"全部 {len(OK)} 项通过")

# -*- coding: utf-8 -*-
"""自定义目标自检：python tests/test_targets.py"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from bqyw.rules import (POINT_ANY, SUIT_SUO, SUIT_TONG, SUIT_UNKNOWN,  # noqa: E402
                        SUIT_WAN, Card)
from bqyw.targets import CustomTarget, estimate_attempts  # noqa: E402

OK = []


def check(name, got, want):
    if got != want:
        raise AssertionError(f"{name}: got {got!r}, want {want!r}")
    OK.append(name)


C = Card

# ---------------------------------------------------------------- 解析
t = CustomTarget.parse("3筒 3筒 3索 5萬 5")
check("普通解析", [(c.point, c.suit) for c in t.cards],
      [(3, SUIT_TONG), (3, SUIT_TONG), (3, SUIT_SUO), (5, SUIT_WAN), (5, SUIT_UNKNOWN)])

t = CustomTarget.parse("任意")
check("任意=不挑点数+不挑花色", [(c.point, c.suit) for c in t.cards],
      [(POINT_ANY, SUIT_UNKNOWN)])

t = CustomTarget.parse("任意筒 任意萬")
check("任意+指定花色", [(c.point, c.suit) for c in t.cards],
      [(POINT_ANY, SUIT_TONG), (POINT_ANY, SUIT_WAN)])

t = CustomTarget.parse("2任意 2")
check("点数固定+花色任意", [(c.point, c.suit) for c in t.cards],
      [(2, SUIT_UNKNOWN), (2, SUIT_UNKNOWN)])

t = CustomTarget.parse("任意 任意 不抽")
check("任意+不抽", len(t.cards), 2)
check("label 显示任意", t.label, "自定义(任意? 任意?)")

for bad in ("6筒", "abc", "任意筒索"):
    try:
        CustomTarget.parse(bad)
    except ValueError:
        OK.append(f"拒绝 {bad}")
    else:
        raise AssertionError(f"{bad} 应该报错")

# ---------------------------------------------------------------- 匹配
five = CustomTarget.parse("3筒 3筒 3索 5萬 5")
check("普通命中", five.matches([C(3, "筒"), C(3, "筒"), C(3, "索"), C(5, "萬"), C(5, "筒")]), True)
check("花色不对不命中", five.matches([C(3, "筒"), C(3, "筒"), C(3, "筒"), C(5, "萬"), C(5, "筒")]), False)

any_suit = CustomTarget.parse("3 3")
check("花色任意", any_suit.matches([C(3, "筒"), C(3, "索")]), True)

# 贪心会错配的那种：目标是「任意筒 + 2筒」，抽到 2筒 + 3筒
tricky = CustomTarget.parse("任意筒 2筒")
check("任意筒+2筒 对 2筒+3筒", tricky.matches([C(2, "筒"), C(3, "筒")]), True)
check("反过来也对", tricky.matches([C(3, "筒"), C(2, "筒")]), True)
check("两张都不是筒", tricky.matches([C(2, "索"), C(3, "索")]), False)
check("只有一个筒", tricky.matches([C(2, "筒"), C(2, "索")]), False)

any_point = CustomTarget.parse("任意 任意")
check("任意两张", any_point.matches([C(1, "筒"), C(5, "萬")]), True)
check("张数不对", any_point.matches([C(1, "筒")]), False)

any_tong = CustomTarget.parse("任意筒 任意筒")
check("两张任意筒", any_tong.matches([C(1, "筒"), C(5, "筒")]), True)
check("混进一张索", any_tong.matches([C(1, "筒"), C(5, "索")]), False)

# ---------------------------------------------------------------- 剪枝（play 过程中的 possible）
seq = CustomTarget.parse("3索 3索 3索")
check("还没牌都还可能", seq.possible([], 5), True)
check("已中一张", seq.possible([C(3, "索")], 4), True)
check("中了张别的就废了", seq.possible([C(3, "筒")], 4), False)

wild = CustomTarget.parse("任意 任意")
check("任意两张不限张数", wild.possible([C(1, "筒")], 4), True)
check("超过目标张数就不行", wild.possible([C(1, "筒"), C(2, "筒"), C(3, "筒")], 2), False)

# ---------------------------------------------------------------- 概率
# 这两组是历史校准值，改算法必须仍然对得上
check("1筒2筒3筒4筒5筒 无序", round(estimate_attempts(CustomTarget.parse("1筒 2筒 3筒 4筒 5筒"))), 4348)
check("1筒2筒3筒4筒5筒 有序",
      round(estimate_attempts(CustomTarget.parse("1筒 2筒 3筒 4筒 5筒", ordered=True))), 521730)
check("五张一样 有序=无序",
      estimate_attempts(CustomTarget.parse("5 5 5 5 5", ordered=True)),
      estimate_attempts(CustomTarget.parse("5 5 5 5 5")))

check("任意一张必中", estimate_attempts(CustomTarget.parse("任意")), 1.0)
check("任意三张必中", estimate_attempts(CustomTarget.parse("任意 任意 任意")), 1.0)

# 「任意筒 任意筒 任意筒 按顺序」= 前三张都是筒；筒共 12 张
p = (12 / 34) * (11 / 33) * (10 / 32)
check("三张任意筒", round(estimate_attempts(CustomTarget.parse("任意筒 任意筒 任意筒", ordered=True)), 1),
      round(1 / p, 1))

print(f"全部 {len(OK)} 项通过")

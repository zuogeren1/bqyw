# -*- coding: utf-8 -*-
"""自定义目标自检：python tests/test_targets.py"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from bqyw.rules import (POINT_ANY, SUIT_SUO, SUIT_TONG, SUIT_UNKNOWN,  # noqa: E402
                        SUIT_WAN, Card)
from bqyw.rules import Hand  # noqa: E402
from bqyw.targets import (CustomTarget, HandTarget, MultiTarget,  # noqa: E402
                          UnionTarget, estimate_attempts)

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

# ---------------------------------------------------------------- 并集
# 主 同花顺 + 保底 顺子：手里 4 张顺子要继续搏，搏不到就收顺子
uni = UnionTarget(HandTarget(Hand.STRAIGHT_FLUSH), HandTarget(Hand.STRAIGHT))
check("并集标题", str(uni), "同花顺 或 顺子")
check("并集最多抽 5 张", uni.stop_count, 5)

four_tong = [C(1, "筒"), C(2, "筒"), C(3, "筒"), C(4, "筒")]
check("4 张顺子但同花顺还有戏 → 不算命中", uni.matches(four_tong, 1), False)
check("4 张顺子 → 还算有戏", uni.possible(four_tong, 1), True)

mixed = four_tong + [C(5, "索")]
check("5 张里 4 张顺子、同花顺没戏 → 收顺子", uni.matches(mixed, 0), True)

five_tong = [C(1, "筒"), C(2, "筒"), C(3, "筒"), C(4, "筒"), C(5, "筒")]
check("同花顺直接命中", uni.matches(five_tong, 0), True)

junk = [C(1, "筒"), C(2, "索"), C(4, "萬"), C(5, "筒")]
check("什么都没有 → 不命中", uni.matches(junk, 1), False)

# 两个目标都不成立才该放弃
dead = [C(1, "筒"), C(1, "筒"), C(4, "萬"), C(4, "索"), C(4, "筒")]
check("同花顺和顺子都没戏", uni.possible(dead, 0), False)

# 保底是「同花」这种一手全算的，凑到 4 张就该收，别把到手的抽没了
fl = UnionTarget(HandTarget(Hand.STRAIGHT_FLUSH), HandTarget(Hand.FLUSH))
four_same_suit = [C(1, "筒"), C(2, "筒"), C(4, "筒"), C(5, "筒")]
check("4 张同花、保底易碎 → 立刻收", fl.matches(four_same_suit, 1), True)

# 主目标先达成时，不该被保底拖住
quad = UnionTarget(HandTarget(Hand.QUADS), HandTarget(Hand.PAIR))
four_of_kind = [C(3, "筒"), C(3, "索"), C(3, "萬"), C(3, "筒")]
check("主目标达成", quad.matches(four_of_kind, 1), True)

try:
    UnionTarget(HandTarget(Hand.QUADS), HandTarget(Hand.QUADS))
    raise AssertionError("目标相同应该报错")
except ValueError:
    OK.append("主保底不能相同")

# 并集成功率要高于主目标单独的成功率（同花顺 1739 次，顺子 5.4 次）
check("并集比同花顺好抽得多", estimate_attempts(uni) < 10, True)

# ---------------------------------------------------------------- 多选
multi = MultiTarget([HandTarget(Hand.STRAIGHT), HandTarget(Hand.FLUSH),
                     HandTarget(Hand.STRAIGHT_FLUSH)])
check("多选按倍率排序", str(multi), "同花顺 / 同花 / 顺子")
check("多选最多抽 5 张", multi.stop_count, 5)

# 4 张杂色顺子：同花和同花顺都已经没戏（花色已杂），收顺子
mixed4 = [C(1, "筒"), C(2, "索"), C(3, "萬"), C(4, "筒")]
check("杂色 4 张顺子 → 收", multi.matches(mixed4, 1), True)

# 4 张同花顺雏形：同花已成（易碎），立刻收，不赌第 5 张
flush4 = [C(1, "筒"), C(2, "筒"), C(3, "筒"), C(4, "筒")]
check("4 张同花 → 立刻收（易碎）", multi.matches(flush4, 1), True)

# 只有顺子可能时，4 张顺子继续搏同花顺
just_straight = MultiTarget([HandTarget(Hand.STRAIGHT), HandTarget(Hand.STRAIGHT_FLUSH)])
mixed4b = [C(1, "筒"), C(2, "索"), C(3, "萬"), C(4, "筒")]
check("杂色 4 张、只剩顺子可搏也无所谓", just_straight.matches(mixed4b, 1), True)

tong4 = [C(1, "筒"), C(2, "筒"), C(3, "筒"), C(4, "筒")]
check("同花顺还有戏 → 继续抽", just_straight.matches(tong4, 1), False)
check("同花顺还有戏 → 还算活", just_straight.possible(tong4, 1), True)
check("第 5 张异色 → 收顺子", just_straight.matches(tong4 + [C(5, "索")], 0), True)

try:
    MultiTarget([HandTarget(Hand.QUADS)])
    raise AssertionError("一个目标应该报错")
except ValueError:
    OK.append("多选至少要两个")

print(f"全部 {len(OK)} 项通过")
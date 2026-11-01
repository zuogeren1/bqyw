package io.github.zuogeren.bqyw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 目标选择的单测：**「散牌」不能再当目标**。
 *
 * 任何一手牌都至少是散牌，勾它等于「第一张就停」；提前剪枝还会打出
 * 「散牌 已不可能，提前放弃」这种自相矛盾的日志。所以：
 *
 *  1. 牌型页不再提供它（[Overlay] 的 HAND_ORDER 里没有 HIGH，这里不做 UI 断言）；
 *  2. 旧数据里的「散牌」在 [Targets.combine] 入口就被剔除 —— 只剩它时返回 null，
 *     由 CaptureService 兜底成 [Targets.FALLBACK_HAND]（四条，和 PC 版默认一致）；
 *  3. [Hands.HIGH] 本身不动：判断「这手牌是什么牌型」时还要用它。
 */
class TargetSelectionTest {

    @Test
    fun highIsFilteredOutOfTargets() {
        assertEquals(emptyList<String>(), Targets.normalizeHands(listOf("HIGH")))
        assertEquals(
            listOf("QUADS", "FLUSH"),
            Targets.normalizeHands(listOf("HIGH", " QUADS ", "FLUSH", "")),
        )
        // 只剩散牌 = 没有目标（服务那边会兜底四条）
        assertNull(Targets.combine(listOf("HIGH"), emptyList(), false))
        // 兜底目标就是四条
        val fallback = Targets.combine(listOf(Targets.FALLBACK_HAND), emptyList(), false)
        assertEquals(Hand.QUADS, (fallback as Target).hand)
        // 正常组合不受影响：两个牌型仍然包成多目标
        val multi = Targets.combine(listOf("QUADS", "HIGH", "FLUSH"), emptyList(), false)
        assertTrue(multi is MultiTarget)
        assertEquals(2L, (multi as MultiTarget).targets.size.toLong())
    }

    @Test
    fun highStillDescribesAHand() {
        // 「散牌」仍是正常的牌型结果：空手、以及 1/3/5 这种凑不出任何牌型的手牌
        assertEquals(Hand.HIGH, Hands.best(emptyList()))
        val junk = listOf(Card(1, 1), Card(3, 2), Card(5, 3))
        assertEquals(Hand.HIGH, Hands.best(junk))
        // 1..5 五张那是顺子，不是散牌 —— 顺带确认这套判定没被改动
        val straight = listOf(Card(1, 1), Card(2, 2), Card(3, 3), Card(4, 1), Card(5, 2))
        assertEquals(Hand.STRAIGHT, Hands.best(straight))
    }
}

package io.github.zuogeren.bqyw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「与第一张同花」：自定义目标第 2 张起可以要求花色跟**第 1 格那张**一样。
 *
 * 用途是刷「整手同色」的牌型（比如五张 5 全同色）。它**不要求勾「按顺序」**：
 * 无序判时先挑一张当第 1 格，把「同」钉成它的花色，剩下的照常派。
 */
class TargetSameSuitTest {

    private val tong = 2
    private val suo = 3

    @Test
    fun parsesSameSuitToken() {
        val t = Targets.parse("5 5同", ordered = true)
        assertEquals(5, t.wants[0].point)
        assertEquals(0, t.wants[0].suit)
        assertEquals(5, t.wants[1].point)
        assertEquals(Target.SUIT_SAME, t.wants[1].suit)
        assertEquals("自定义（5 5同，按顺序）", t.toString())
    }

    @Test
    fun matchesChainsAlongActualCards() {
        val t = Targets.parse("5 5同 5同 5同", ordered = true)
        assertTrue(t.matches(listOf(Card(5, tong), Card(5, tong), Card(5, tong), Card(5, tong))))
        // 第 1 张随便什么花色都行，后面跟着它走
        assertTrue(t.matches(listOf(Card(5, suo), Card(5, suo), Card(5, suo), Card(5, suo))))
        // 中途换色就断了
        assertFalse(t.matches(listOf(Card(5, tong), Card(5, tong), Card(5, suo), Card(5, suo))))
        // 花色认不出（0）不算同色：否则会把「两个都没认出来」当成命中
        assertFalse(t.matches(listOf(Card(5, 0), Card(5, 0), Card(5, tong), Card(5, tong))))
    }

    @Test
    fun firstSlotMayPinASuit() {
        val t = Targets.parse("5筒 5同 5同", ordered = true)
        assertTrue(t.matches(listOf(Card(5, tong), Card(5, tong), Card(5, tong))))
        assertFalse(t.matches(listOf(Card(5, suo), Card(5, suo), Card(5, suo))))
    }

    @Test
    fun possibleUsesFirstCard() {
        val t = Targets.parse("5 5同", ordered = true)
        assertTrue(t.possible(listOf(Card(5, suo), Card(5, suo)), 3))
        assertFalse(t.possible(listOf(Card(5, suo), Card(5, tong)), 3))
        // 第 1 张就不对，直接废
        assertFalse(t.possible(listOf(Card(4, suo)), 4))
    }

    @Test
    fun worksWithoutOrdered() {
        val t = Targets.parse("5 5同")            // 没勾按顺序
        assertFalse(t.ordered)
        assertTrue(t.matches(listOf(Card(5, suo), Card(5, suo))))
        assertFalse(t.matches(listOf(Card(5, suo), Card(5, tong))))
        // 先挑哪张当第 1 格都行，同色就成立
        assertTrue(Targets.parse("5 5同").matches(listOf(Card(5, tong), Card(5, tong))))
        // 第 1 格钉了花色，就得跟那个花色
        assertFalse(Targets.parse("5筒 5同").matches(listOf(Card(5, suo), Card(5, suo))))
        // 第 1 格的点数照旧要满足
        assertTrue(Targets.parse("3 5同").matches(listOf(Card(5, suo), Card(3, suo))))
        assertFalse(Targets.parse("3 5同").matches(listOf(Card(5, suo), Card(4, suo))))
    }

    @Test
    fun possibleWithoutOrdered() {
        val t = Targets.parse("5 5同 5同")
        assertTrue(t.possible(emptyList(), 5))
        assertTrue(t.possible(listOf(Card(5, tong)), 4))
        assertFalse(t.possible(listOf(Card(5, tong), Card(5, suo)), 3))
    }

    @Test
    fun firstSlotCannotAskForSame() {
        val e = runCatching {
            Target(listOf(Card(5, Target.SUIT_SAME)), null, true)
        }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
    }

    @Test
    fun anyPointWithSameSuitIsExpressible() {
        // 悬浮球那几格按这个写法拼 spec：点数「任」+ 花色「同」也要能表达
        val t = Targets.parse("5 任同", ordered = true)
        assertEquals(0, t.wants[1].point)
        assertEquals(Target.SUIT_SAME, t.wants[1].suit)
        assertEquals("自定义（5 任同，按顺序）", t.toString())
    }
}

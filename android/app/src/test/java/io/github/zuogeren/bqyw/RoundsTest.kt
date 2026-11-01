package io.github.zuogeren.bqyw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 轮数记账的单测：**一次发牌 = 一轮**（PC 口径）。
 *
 * 真机日志里出现过「5 张 / 0 轮，平均 10.9s/轮」—— 原因是旧代码只在「读到 5 张满手牌」
 * 的分支里 `attempts++`，而 2 张就判「已不可能」提前放弃的局走了另一条路，从没记过账。
 * 这里把新口径钉死：
 *
 *  1. 同一轮里抽几张都只记一次，提前放弃的局照样算一轮；
 *  2. 牌桌清空之后的下一次发牌才是新一轮；
 *  3. 到重开上限就不再往上加（[Rounds.armed] 保持 false，调用方据此停手）。
 *
 * 顺带验一下结束摘要 / [节奏] 行的格式（0 轮时不写「平均 s/轮」这种假数）。
 */
class RoundsTest {

    @Test
    fun oneRoundPerDealEvenIfAbandonedEarly() {
        val rounds = Rounds(0)
        val log = ArrayList<String>()
        // 真机那段：发牌 → 读 2~3 帧 → 判「已不可能」→ 放弃 → 牌桌清空，连着三轮
        for (i in 1..3) {
            for (filled in 1..3) {
                val no = rounds.tick(filled)
                if (no > 0) log.add("[第 " + no + " 轮]")
            }
            assertEquals("第 " + i + " 轮之后应该记到 " + i + " 轮", i.toLong(), rounds.started.toLong())
            assertEquals("放弃后牌桌清空 → 解锁", 0L, rounds.tick(0).toLong())
        }
        assertEquals("弃三轮 = 3 轮，不是 0 轮", 3L, rounds.started.toLong())
        assertEquals(listOf("[第 1 轮]", "[第 2 轮]", "[第 3 轮]"), log)

        // 结束摘要 / 节奏行：轮数跟着发牌走（这两行是生产代码同一份格式）
        val summary = summaryLine(11.0, rounds.started, 5, 3, 1.42, 0)
        val pace = paceLine(5, rounds.started, 640, 60, 150, 850, 3, 1.42)
        println(log.joinToString("\n"))
        println(pace)
        println(summary)
        assertTrue(summary, summary.contains("3 轮"))
        assertTrue(summary, summary.contains("平均 3.7s/轮"))
        assertTrue(pace, pace.contains("5 张 / 3 轮"))

        // 一轮都没开时不能编「平均 10.9s/轮」这种数
        val empty = summaryLine(11.0, 0, 0, 0, 1.42, 0)
        assertFalse(empty, empty.contains("平均"))
        assertTrue(empty, empty.contains("0 轮"))
    }

    @Test
    fun sameHandIsCountedOnce() {
        val rounds = Rounds(0)
        val seq = intArrayOf(1, 2, 2, 3, 4, 5, 5)
        val counted = seq.map { rounds.tick(it) }.filter { it > 0 }
        assertEquals("一轮只记一次", 1L, counted.size.toLong())
        assertEquals(1L, rounds.started.toLong())
        // 抽满之后牌桌还没清空：再来一帧也不算新一轮
        assertEquals(0L, rounds.tick(5).toLong())
        assertEquals(1L, rounds.started.toLong())
    }

    @Test
    fun limitStopsNewRounds() {
        val rounds = Rounds(2)
        assertEquals(1L, rounds.tick(1).toLong())
        assertFalse("开满 2 轮之前没到顶", rounds.exhausted)
        rounds.tick(0)
        assertEquals(2L, rounds.tick(1).toLong())
        assertTrue("2 轮开满了", rounds.exhausted)
        rounds.tick(0)
        assertEquals("到顶之后不再记账", 0L, rounds.tick(1).toLong())
        assertEquals(2L, rounds.started.toLong())
        assertFalse("开不了新一轮时 armed 必须是 false", rounds.armed)
    }
}

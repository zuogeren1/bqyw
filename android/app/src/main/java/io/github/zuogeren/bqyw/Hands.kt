package io.github.zuogeren.bqyw

import kotlin.math.min

/** 牌型：顺序和倍率跟 PC 版 rules.py 完全一致。 */
enum class Hand(val cn: String, val mult: Double) {
    HIGH("散牌", 1.0),
    PAIR("对子", 1.2),
    TWO_PAIR("两对", 1.4),
    TRIPS("三条", 1.6),
    STRAIGHT("顺子", 1.8),
    FULL_HOUSE("葫芦", 2.0),
    FLUSH("同花", 4.0),
    QUADS("四条", 6.0),
    STRAIGHT_FLUSH("同花顺", 8.0),
}

/** 一张牌：点数 1..5，花色 1=萬 2=筒 3=索，0=没翻开或认不出。 */
data class Card(val point: Int, val suit: Int)

object Hands {

    /** 战力点 -> 奖励点底分（官方表格）。 */
    private val BASE = intArrayOf(
        0, 1000, 3000, 6000, 10000, 15000,
        20000, 25000, 30000, 35000, 40000,
        50000, 60000, 70000, 80000, 90000,
        100000, 110000, 120000, 130000, 150000,
    )

    private fun runs(points: List<Int>): Int {
        val u = points.toSortedSet().toList()
        if (u.isEmpty()) return 0
        var best = 1
        var cur = 1
        for (i in 1 until u.size) {
            cur = if (u[i] == u[i - 1] + 1) cur + 1 else 1
            if (cur > best) best = cur
        }
        return best
    }

    /** 同时满足的全部牌型（不做取舍，和 PC 版一致）。 */
    fun all(cards: List<Card>): Set<Hand> {
        if (cards.isEmpty()) return setOf(Hand.HIGH)
        val pts = cards.map { it.point }
        val cnt = pts.groupingBy { it }.eachCount().values.sortedDescending()
        val n = cards.size
        val out = mutableSetOf(Hand.HIGH)

        val quads = cnt.contains(4) || cnt.contains(5)
        val trips = cnt.contains(3)
        val pairs = cnt.count { it == 2 }

        if (quads) out.add(Hand.QUADS)
        if (trips && pairs >= 1) out.add(Hand.FULL_HOUSE)
        if (trips) out.add(Hand.TRIPS)
        if (pairs >= 2) out.add(Hand.TWO_PAIR) else if (pairs == 1) out.add(Hand.PAIR)
        if (runs(pts) >= 4) out.add(Hand.STRAIGHT)

        val same = cards[0].suit != 0 && cards.all { it.suit == cards[0].suit }
        if (n >= 4 && same) {
            out.add(Hand.FLUSH)
            if (n >= 5 && runs(pts) >= 5) out.add(Hand.STRAIGHT_FLUSH)
        }
        return out
    }

    fun best(cards: List<Card>): Hand =
        all(cards).maxByOrNull { it.mult } ?: Hand.HIGH

    /** 奖励点：底分 × 牌型倍率；命中今日好运组合再 ×2。 */
    fun score(cards: List<Card>, lucky: Hand? = null): Int {
        if (cards.isEmpty()) return 0
        val h = best(cards)
        var mult = h.mult
        if (lucky != null && h == lucky) mult *= 2
        val base = BASE[min(cards.sumOf { it.point }, 20)]
        return (base * mult).toInt()
    }
}

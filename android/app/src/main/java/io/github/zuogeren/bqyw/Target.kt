package io.github.zuogeren.bqyw

/**
 * 目标：要么按「点数组合」（0 表示任意），要么按「牌型」。
 * 两者同时给的时候，牌型优先（牌型判定本身已经含点数关系）。
 */
class Target(val points: List<Int>, val hand: Hand? = null) {

    fun matches(cards: List<Card>): Boolean {
        if (hand != null) return Hands.best(cards) == hand
        if (cards.size < points.size) return false
        return fit(points.toMutableList(), cards.take(points.size).map { it.point })
    }

    private fun fit(left: MutableList<Int>, cards: List<Int>): Boolean {
        if (left.isEmpty()) return cards.isEmpty()
        if (cards.isEmpty()) return false
        val c = cards[0]
        for (i in left.indices) {
            if (left[i] == 0 || left[i] == c) {
                val rest = ArrayList(left)
                rest.removeAt(i)
                if (fit(rest, cards.subList(1, cards.size))) return true
            }
        }
        return false
    }

    override fun toString(): String {
        if (hand != null) return hand.cn
        return points.joinToString(" ") { if (it == 0) "任意" else it.toString() }
    }
}

object Targets {

    /** 解析 "5 5 5 5 任意" 这种写法。 */
    fun parse(spec: String): Target {
        val raw = spec.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        require(raw.isNotEmpty()) { "目标不能为空" }
        val pts = raw.map { tok ->
            when (tok) {
                "任意", "?", "any" -> 0
                else -> {
                    val v = tok.toIntOrNull()
                    require(v != null && v in 1..5) { "看不懂的点数：" + tok }
                    v
                }
            }
        }
        return Target(pts)
    }
}

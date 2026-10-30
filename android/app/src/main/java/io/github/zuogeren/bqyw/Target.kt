package io.github.zuogeren.bqyw

/** 点数目标：0 表示任意。花色先不管，只比点数。 */
class PointTarget(val points: List<Int>) {

    val count: Int get() = points.size

    fun matches(cards: List<Int>): Boolean {
        if (cards.size < count) return false
        return fit(points.toMutableList(), cards.take(count))
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

    override fun toString(): String =
        points.joinToString(" ") { if (it == 0) "任意" else it.toString() }
}

object Targets {

    /** 解析 "5 5 5 5 任意" 这种写法。 */
    fun parse(spec: String): PointTarget {
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
        return PointTarget(pts)
    }
}

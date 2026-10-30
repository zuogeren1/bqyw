package io.github.zuogeren.bqyw

/**
 * 目标：要么按「牌型」，要么按「点数组合」（可以带花色）。
 * 选了牌型就按牌型判定，点数那组直接忽略。
 */
class Target(val wants: List<Card>, val hand: Hand? = null) {

    fun matches(cards: List<Card>): Boolean {
        if (hand != null) return Hands.best(cards) == hand
        if (cards.size < wants.size) return false
        return fit(wants.toMutableList(), cards.take(wants.size))
    }

    private fun fit(left: MutableList<Card>, cards: List<Card>): Boolean {
        if (left.isEmpty()) return cards.isEmpty()
        if (cards.isEmpty()) return false
        val c = cards[0]
        for (i in left.indices) {
            val w = left[i]
            val pointOk = w.point == 0 || w.point == c.point
            val suitOk = w.suit == 0 || w.suit == c.suit
            if (pointOk && suitOk) {
                val rest = ArrayList(left)
                rest.removeAt(i)
                if (fit(rest, cards.subList(1, cards.size))) return true
            }
        }
        return false
    }

    override fun toString(): String {
        if (hand != null) return hand.cn
        return wants.joinToString(" ") { w ->
            when {
                w.point == 0 -> "任意"
                w.suit == 0 -> w.point.toString()
                else -> w.point.toString() + SUIT_CN[w.suit]
            }
        }
    }

    companion object {
        val SUIT_CN = arrayOf("", "萬", "筒", "索")
    }
}

object Targets {

    /** 解析 "3筒 2 5 任 任" 这种写法：不写花色就是不限。 */
    fun parse(spec: String): Target {
        val raw = spec.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        require(raw.isNotEmpty()) { "目标不能为空" }
        return Target(raw.map { card(it) })
    }

    private fun card(tok: String): Card {
        if (tok == "任意" || tok == "任" || tok == "?" || tok == "any") return Card(0, 0)
        val m = Regex("^([1-5])([萬筒索]?)$").find(tok)
            ?: throw IllegalArgumentException("看不懂：" + tok)
        val p = m.groupValues[1].toInt()
        val s = when (m.groupValues[2]) {
            "萬" -> 1
            "筒" -> 2
            "索" -> 3
            else -> 0
        }
        return Card(p, s)
    }
}

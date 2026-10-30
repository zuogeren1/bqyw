package io.github.zuogeren.bqyw

/**
 * 目标：要么按「牌型」，要么按「点数组合」（可以带花色）。
 * 选了牌型就按牌型判定，点数那组直接忽略。
 */
class Target(val wants: List<Card>, val hand: Hand? = null, val ordered: Boolean = false) {

    fun matches(cards: List<Card>): Boolean {
        if (hand != null) return Hands.best(cards) == hand
        if (cards.size < wants.size) return false
        if (ordered) {
            for (i in wants.indices) {
                if (!one(cards[i], wants[i])) return false
            }
            return true
        }
        return fit(wants.toMutableList(), cards.take(wants.size))
    }

    /**
     * 还剩 left 抽，还有没有可能达成。
     *
     * 和 [matches] 的区别：这里只问「已经抽到的牌放不放得进目标位」，
     * 剩下的空位留给后面抽。给 false 就说明这手牌已经废了，可以提前重开。
     */
    fun possible(cards: List<Card>, left: Int): Boolean {
        if (hand != null) return Deck.reachable(cards, hand, left)
        if (cards.size > wants.size) return false
        if (ordered) {
            for (i in cards.indices) {
                if (!one(cards[i], wants[i])) return false
            }
            return true
        }
        return fits(wants.toMutableList(), cards)
    }

    private fun one(c: Card, w: Card): Boolean =
        (w.point == 0 || w.point == c.point) && (w.suit == 0 || w.suit == c.suit)

    /** 把 drawn 一张张派进 slots，派得进去就叫还有戏（空位后面再抽）。 */
    private fun fits(slots: MutableList<Card>, drawn: List<Card>): Boolean {
        if (drawn.isEmpty()) return true
        for (i in slots.indices) {
            if (!one(drawn[0], slots[i])) continue
            val rest = ArrayList(slots)
            rest.removeAt(i)
            if (fits(rest, drawn.subList(1, drawn.size))) return true
        }
        return false
    }

    private fun fit(left: MutableList<Card>, cards: List<Card>): Boolean {
        if (left.isEmpty()) return cards.isEmpty()
        if (cards.isEmpty()) return false
        val c = cards[0]
        for (i in left.indices) {
            val w = left[i]
            if (one(c, w)) {
                val rest = ArrayList(left)
                rest.removeAt(i)
                if (fit(rest, cards.subList(1, cards.size))) return true
            }
        }
        return false
    }

    override fun toString(): String {
        if (hand != null) return hand.cn
        val body = describe()
        return if (ordered) body + "（按顺序）" else body
    }

    private fun describe(): String {
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

package io.github.zuogeren.bqyw

/**
 * 牌堆 + 「还来不来得及」的判定。
 *
 * 牌堆数据来自游戏右侧的「铭牌库剩余」：战力点 1~5 各 6/6/6/6/10 张，共 34 张。
 * 花色在点数内的分配界面不显示，按均分估（只有 5 点给了 3/4/3），
 * 影响的是同花这一档，占比很小。
 *
 * 自动抽牌只关心「还有没有可能」，不关心概率，所以这里做的是穷举存在性：
 * 把接下来可能抽到的牌全试一遍，只要有一种能凑成目标就算有戏。
 */
object Deck {

    /** [点数][花色] 的初始张数，0 号位不用。花色 1=萬 2=筒 3=索。 */
    private val STOCK = arrayOf(
        intArrayOf(0, 0, 0, 0),
        intArrayOf(0, 2, 2, 2),
        intArrayOf(0, 2, 2, 2),
        intArrayOf(0, 2, 2, 2),
        intArrayOf(0, 2, 2, 2),
        intArrayOf(0, 3, 4, 3),
    )

    /** 格子数：5 个点数 × 3 种花色。 */
    private const val CELLS = 15

    /**
     * 手上这些牌，再抽 left 张，有没有可能凑成 hand。
     *
     * 判定口径和匹配保持一致：要的是「最终 5 张的生效牌型 == hand」，
     * 不是「中途出现过 hand」。比如已经三条了，后面两张再怎么抽都是三条以上，
     * 目标是对子的话这里直接给 false。
     */
    fun reachable(cards: List<Card>, hand: Hand, left: Int): Boolean {
        if (left <= 0) return Hands.best(cards) == hand
        val stock = IntArray(CELLS) { STOCK[it / 3 + 1][it % 3 + 1] }
        for (c in cards) takeOut(stock, c)
        return search(ArrayList(cards), stock, hand, left, 0)
    }

    /** 扣掉手里这张牌；花色认不出就摊到该点数剩得最多的花色上（和 PC 版一样）。 */
    private fun takeOut(stock: IntArray, c: Card) {
        if (c.point !in 1..5) return
        val base = (c.point - 1) * 3
        if (c.suit in 1..3) {
            val i = base + c.suit - 1
            if (stock[i] > 0) {
                stock[i]--
                return
            }
        }
        var best = base
        for (j in 1 until 3) if (stock[base + j] > stock[best]) best = base + j
        if (stock[best] > 0) stock[best]--
    }

    /** 只往编号不小于 from 的格子里接着取，这样同一手牌的不同抽取顺序只枚举一次。 */
    private fun search(
        cur: MutableList<Card>,
        stock: IntArray,
        hand: Hand,
        left: Int,
        from: Int,
    ): Boolean {
        if (left == 0) return Hands.best(cur) == hand
        for (i in from until CELLS) {
            if (stock[i] <= 0) continue
            stock[i]--
            cur.add(Card(i / 3 + 1, i % 3 + 1))
            val ok = search(cur, stock, hand, left - 1, i)
            cur.removeAt(cur.size - 1)
            stock[i]++
            if (ok) return true
        }
        return false
    }
}

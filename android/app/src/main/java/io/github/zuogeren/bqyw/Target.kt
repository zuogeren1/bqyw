package io.github.zuogeren.bqyw

/**
 * 目标：标准牌型、自定义牌面、多目标都实现这个接口，Bot 只认它。
 */
interface TargetBase {

    /** 抽到第几张就该结算：标准牌型一律 5 张，自定义是它自己的张数（「不抽」截短）。 */
    val stopCount: Int

    /**
     * 是不是「整手都算数」的目标（同花 / 同花顺 / 自定义）。
     *
     * 这类目标多抽一张就可能没了，手牌已经凑到就得马上收，不能为了搏更高的倍率再赌一张
     * （和 PC 版 fragile 是同一套口径）。
     */
    val fragile: Boolean

    /** 这一手算不算达成。[left] 是还能再抽几张，只有 [possible] 和易碎判断用得上。 */
    fun matches(cards: List<Card>, left: Int = 0): Boolean

    /** 还剩 [left] 抽，还有没有可能达成。 */
    fun possible(cards: List<Card>, left: Int): Boolean

    /**
     * 现在就收手吗。
     *
     * 单个目标还是原来的口径：抽够张数（牌型就是 5 张）才判；多目标会提前，
     * 见 [MultiTarget.shouldStop]。
     */
    fun shouldStop(cards: List<Card>, left: Int): Boolean =
        cards.size >= stopCount && matches(cards, left)
}

/**
 * 一个目标：要么按「牌型」，要么按「点数组合」（可以带花色）。
 * 选了牌型就按牌型判定，点数那组直接忽略。
 */
data class Target(
    val wants: List<Card>,
    val hand: Hand? = null,
    val ordered: Boolean = false,
) : TargetBase {

    override val stopCount: Int
        get() = if (hand != null) MAX_DRAWS else wants.size

    /** 自定义张数固定，多抽一张就不再成立；同花/同花顺看的是「所有牌」，多一张异色就废。 */
    override val fragile: Boolean
        get() = hand == null || hand == Hand.FLUSH || hand == Hand.STRAIGHT_FLUSH

    override fun matches(cards: List<Card>, left: Int): Boolean {
        if (cards.isEmpty()) return false
        if (hand != null) return Hands.best(cards) == hand
        // 张数对不上就不算：自定义目标要的就是这一手刚刚好这么多张
        if (cards.size != wants.size) return false
        if (ordered) {
            for (i in wants.indices) {
                if (!one(cards[i], wants[i])) return false
            }
            return true
        }
        return fit(wants.toMutableList(), cards)
    }

    /**
     * 还剩 left 抽，还有没有可能达成。
     *
     * 和 [matches] 的区别：这里只问「已经抽到的牌放不放得进目标位」，
     * 剩下的空位留给后面抽。给 false 就说明这手牌已经废了，可以提前重开。
     */
    override fun possible(cards: List<Card>, left: Int): Boolean {
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
        return if (ordered) "自定义（" + body + "，按顺序）" else "自定义（" + body + "）"
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
        /** 一手最多抽几张：牌型目标一律按 5 张算。 */
        const val MAX_DRAWS = 5

        val SUIT_CN = arrayOf("", "萬", "筒", "索")
    }
}

/**
 * 多目标：勾几个都行，达成其中任意一个就算命中。
 *
 * 排队规则和 PC 版一致：标准牌型按倍率从高到低，自定义垫底。判定分两步：
 * 先把已经到手、多抽一张就会碎的目标（同花 / 同花顺 / 自定义）收下，
 * 再按优先级逐个看——这个成了就收，还有戏就继续抽，彻底没戏才轮到下一个。
 *
 * 典型用法「同花顺 + 顺子」：手里 4 张顺子照样抽第 5 张，抽到同花顺最好，
 * 抽不到顺子也还在；而「同花顺 + 同花」手里 4 张同花就得马上收，再抽一张异色就没了。
 */
class MultiTarget(pool: List<TargetBase>) : TargetBase {

    /** 去重后按「越想要越靠前」排好；同档的保持传进来的顺序（稳定排序）。 */
    val targets: List<TargetBase> = pool.distinct().sortedByDescending { desire(it) }

    init {
        require(this.targets.size >= 2) { "多目标至少要两个" }
    }

    /** 抽得最多的那个决定最多抽几张（自定义目标可以只要前几张，按它自己的张数算）。 */
    override val stopCount: Int get() = targets.maxOf { it.stopCount }

    /** 多目标本身不参与易碎判断，碎不碎要看拆开的每个目标。 */
    override val fragile: Boolean get() = false

    override fun matches(cards: List<Card>, left: Int): Boolean {
        if (cards.isEmpty()) return false
        // 1) 易碎目标先保住：整手都算数的目标，凑到手就收，别抽没了
        for (t in targets) {
            if (t.fragile && t.matches(cards, left)) return true
        }
        // 2) 从最想要的往下找：成了就收，还有戏就继续抽，彻底没戏才看下一个
        for (t in targets) {
            if (t.matches(cards, left)) return true
            if (t.possible(cards, left)) return false
        }
        return false
    }

    override fun possible(cards: List<Card>, left: Int): Boolean =
        targets.any { it.matches(cards, left) || it.possible(cards, left) }

    /**
     * 多目标可以比单个目标提前收：易碎目标凑到就得停，不然再抽一张就没了。
     * 单个牌型不提前，还是抽满 5 张再判（保持原来的手感）。
     */
    override fun shouldStop(cards: List<Card>, left: Int): Boolean = matches(cards, left)

    override fun toString(): String = targets.joinToString(" / ")

    /** 越想要的排越前：标准牌型按基础倍率，自定义排最后。 */
    private fun desire(t: TargetBase): Double = (t as? Target)?.hand?.mult ?: 0.0
}

object Targets {

    /** 解析 "3筒 2 5 任 任" 这种写法：不写花色就是不限。 */
    fun parse(spec: String, ordered: Boolean = false): Target {
        val raw = spec.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        require(raw.isNotEmpty()) { "目标不能为空" }
        return Target(raw.map { card(it) }, null, ordered)
    }

    /**
     * 把界面上选的东西拼成一个目标：牌型名（[Hand.name]）+ 每组自定义的牌面。
     *
     * 悬浮球的摘要和真正开跑都用这一个方法，免得「显示的目标」和「跑的目标」对不上。
     * 一个都没选返回 null，由调用方决定是提示还是拒绝。
     */
    fun combine(handNames: List<String>, specs: List<String>, ordered: Boolean): TargetBase? {
        val pool = ArrayList<TargetBase>()
        for (n in handNames) {
            val h = Hand.values().firstOrNull { it.name == n }
                ?: throw IllegalArgumentException("不认识的牌型：" + n)
            pool += Target(emptyList(), h)
        }
        for (s in specs) pool += parse(s, ordered)
        val uniq = pool.distinct()
        return when (uniq.size) {
            0 -> null
            1 -> uniq[0]
            else -> MultiTarget(uniq)
        }
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

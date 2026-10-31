package io.github.zuogeren.bqyw

import android.graphics.Bitmap
import java.util.Locale

/**
 * 抽牌状态机：读牌 → 命中就停 → 没命中就放弃重开。
 *
 * 全部通过 Engine 接口跟外界打交道，方便以后换数据源或写测试。
 */
class Bot(
    private val engine: Engine,
    private val target: TargetBase,
    private val maxAttempts: Int,
    private val fast: Boolean = false,
    private val log: (String) -> Unit,
) {

    /**
     * 能不能「连点铺满 5 张」。
     *
     * 自定义目标只要前几张时（「不抽」），多抽一张就不是这个目标了，
     * 所以哪怕开了快速模式也只能一张一张来。
     */
    private val burstMode = fast && target.stopCount >= Target.MAX_DRAWS

    /** 已经读到手牌的有几轮。 */
    private var attempts = 0

    /** 累计翻出来的牌数、点了多少下「抽牌」，结束摘要用。 */
    private var cardsDrawn = 0
    private var drawTaps = 0

    /** 上一帧的已抽张数，用来算这一帧多翻了几张。 */
    private var prevCards = 0

    private var startedAt = 0L

    interface Engine {
        fun frame(): Bitmap?
        fun tap(x: Int, y: Int): Boolean
        fun beep()
        fun isStopped(): Boolean
        fun saveDebug(bmp: Bitmap, name: String)
    }

    fun run(): String {
        startedAt = System.currentTimeMillis()
        var idle = 0
        var noFrame = 0
        var noRound = 0
        var badRound = 0
        var lastHand = ""
        var lastHandAt = 0L
        var lastFilled = 0
        var noGain = 0
        // 刚放弃完的一小段时间里，牌桌上的牌可能是正在淡出的旧牌，读到不算数
        var calmUntil = 0L
        while (!engine.isStopped()) {
            val bmp = shot()
            if (bmp == null) {
                noFrame++
                if (noFrame % 20 == 0) log("   [!] 拿不到画面（抓屏停了？）")
                if (noFrame > 200) {
                    log("   [!] 长时间没有画面，停止")
                    return "noframe"
                }
                sleep(150)
                continue
            }
            noFrame = 0

            // 弹窗盖住了画面：先确认掉，这时候读什么都是错的（整块白）
            if (isModal(bmp)) {
                bmp.recycle()
                log("   发现放弃确认弹窗，先确认掉")
                val cp = Geom.tapConfirm()
                act("确认放弃", cp.first, cp.second)
                engine.tap(cp.first, cp.second)
                // 点完确认牌会淡出，这时候读到的是上一手牌，直接当成一轮会误判命中
                waitClear(2500)
                continue
            }

            val rnd = Badge.read(bmp)
            if (rnd == 0) {
                bmp.recycle()
                noRound++
                // 刚换轮的瞬间徽章那块会白一下，第一次读空不刷日志，
                // 否则悬浮球的状态栏每轮都要闪一次「读不到轮次」
                if (noRound % 12 == 6) log("   [!] 读不到轮次（" + Badge.lastInfo + "）")
                if (noRound > 40) {
                    log("   [!] 一直读不到轮次，停止")
                    return "no_round"
                }
                sleep(250)
                continue
            }
            noRound = 0
            if (rnd != 1) {
                bmp.recycle()
                badRound++
                if (badRound < 2) {
                    sleep(150)
                    continue
                }
                val keep = engine.frame()
                if (keep != null) {
                    engine.saveDebug(keep, "debug-round2.png")
                    keep.recycle()
                }
                // 不是错误，是被规则挡住：措辞和 PC 版同一句
                log("第 " + rnd + " 轮：这里有牌时「放弃挑战」等于退出整场演算，" +
                    "不会清牌重来。已停止，剩下的交给你。")
                return "round2"
            }
            badRound = 0

            val cards = CardReader.readCards(bmp)
            bmp.recycle()
            val pts = cards.map { it.point }.toIntArray()
            val filled = pts.count { it > 0 }
            // 只统计真翻出来的牌（不是点击次数），结束摘要里的「张牌」按这个算
            if (filled > prevCards) cardsDrawn += filled - prevCards
            prevCards = filled
            if (filled > 0 && System.currentTimeMillis() < calmUntil) {
                sleep(120)
                continue
            }
            // 目标判定只认「已经抽到的牌」。
            // CardReader 永远给 5 格，没翻开的是 Card(0, 0)；空位混进判定里会被当成真的
            // 0 点牌：3 个空位能让 Hands 判出三条、4 张同花因为空位花色不同永远判不出来、
            // 张数固定的自定义目标也永远对不上。所以这里切一份只含已抽牌的手牌。
            val drawn = cards.take(filled)
            // 中间有张牌没认出来时，drawn 里会夹着空位；这种残缺手牌不拿去做判定，
            // 否则「还有戏」会被误判成「已不可能」，白白放弃一轮
            val intact = drawn.none { it.point == 0 }
            // 自定义目标可能只要前几张（「不抽」），牌型目标一律 5 张
            val stop = target.stopCount
            val left = (stop - filled).coerceAtLeast(0)
            // 易碎目标凑到手就得收：这会儿牌还没铺满，但再抽一张（同花 / 自定义）就没了。
            // 单个牌型不走这条路，还是抽满 5 张再判，免得改了原来的手感。
            if (intact && filled in 1 until stop && target.shouldStop(drawn, left)) {
                log("命中 " + target + "：" + cardsText(drawn) +
                    "  战力点 " + pts.sum() + "  预估奖励点 " + Hands.score(drawn))
                engine.beep()
                return "hit"
            }
            if (filled < stop) {
                if (filled == 0) {
                    idle++
                    lastFilled = 0
                    noGain = 0
                    if (idle > 40) {
                        log("   [!] 牌桌一直是空的，停止")
                        return "stuck"
                    }
                } else {
                    idle = 0
                    // 连着抽几次都没多出一张：多半是有张牌没认出来，别一直空点下去
                    if (filled <= lastFilled) {
                        noGain++
                        if (noGain >= (if (fast) 3 else 8)) {
                            log("   [!] 连续 " + noGain + " 次抽牌都没多出一张，停止")
                            return "stuck_draw"
                        }
                    } else {
                        noGain = 0
                    }
                    lastFilled = filled
                    // 提前剪枝：剩下这几抽怎么都凑不出目标了，直接重开，别浪费时间
                    // （连点铺满的快速模式刻意不做，用剪枝换吞吐）
                    if (!burstMode && intact && !target.possible(drawn, left)) {
                        log("     " + target + " 已不可能，提前放弃")
                        forfeit()
                        calmUntil = System.currentTimeMillis() + 1500
                        continue
                    }
                }
                if (burstMode) {
                    burst()
                } else {
                    val p = Geom.tapDraw()
                    act("抽牌", p.first, p.second)
                    engine.tap(p.first, p.second)
                    drawTaps++
                    sleep(850)
                }
                continue
            }
            idle = 0
            lastFilled = 0
            noGain = 0
            // 同一手牌在极短时间内又读到：多半是放弃没生效，别当成新的一轮
            val handKey = pts.joinToString(",")
            val now = System.currentTimeMillis()
            if (handKey == lastHand && now - lastHandAt < 3000) {
                sleep(400)
                continue
            }
            lastHand = handKey
            lastHandAt = now
            attempts++
            // 短自定义目标命中时手上还不到 5 张，牌型也只能按实际抽到的牌算
            val hand = Hands.best(drawn)
            log("[第 " + attempts + " 轮]")
            log(
                "   [" + drawn.size + "张] " + cardsText(drawn) +
                    " 牌型=" + hand.cn + " 战力点=" + pts.sum(),
            )
            if (intact && target.matches(drawn, left)) {
                log("命中 " + target + "：" + cardsText(drawn) +
                    "  战力点 " + pts.sum() + "  预估奖励点 " + Hands.score(drawn))
                engine.beep()
                return "hit"
            }
            if (maxAttempts > 0 && attempts >= maxAttempts) {
                log("达到重开上限 " + maxAttempts + "，停止")
                return "max"
            }
            if (burstMode) {
                // 快速模式：确认完直接开下一轮连点，不读牌也不等淡出
                forfeitFast()
                burst()
            } else {
                forfeit()
                calmUntil = System.currentTimeMillis() + 1500
            }
        }
        return "stopped"
    }

    /**
     * 结束摘要，写法和 PC 版一致：
     * 用时 602s，105 轮，平均 5.7s/轮，587 张牌，1.02s/张（点击 1.22 次/张）。
     * 一张牌都没翻出来时后面几项整项不写 —— 算不出来的不编。
     */
    fun summary(): String {
        val secs = (System.currentTimeMillis() - startedAt) / 1000.0
        val sb = StringBuilder("用时 ").append(Math.round(secs)).append("s，")
        sb.append(attempts).append(" 轮，平均 ")
            .append(f(secs / attempts.coerceAtLeast(1), 1)).append("s/轮")
        if (cardsDrawn > 0) {
            sb.append("，").append(cardsDrawn).append(" 张牌，")
                .append(f(secs / cardsDrawn, 2)).append("s/张")
            sb.append("（点击 ").append(f(drawTaps.toDouble() / cardsDrawn, 2))
                .append(" 次/张）")
        }
        return sb.toString()
    }

    private fun forfeit() {
        val t0 = System.currentTimeMillis()
        val fp = Geom.tapForfeit()
        act("放弃挑战", fp.first, fp.second)
        engine.tap(fp.first, fp.second)
        val t1 = System.currentTimeMillis()
        if (!waitModal(1500)) {
            log("   [!] 没等到放弃确认弹窗，这一下可能点空了")
            return
        }
        val t2 = System.currentTimeMillis()
        // 黄色的确认键刚弹出来时还在落位动画里，这一下会被吞掉
        sleep(150)
        val cp = Geom.tapConfirm()
        act("确认放弃", cp.first, cp.second)
        engine.tap(cp.first, cp.second)
        val t3 = System.currentTimeMillis()
        if (!waitClear(3000)) {
            log("   [!] 确认后牌桌没清空，补点一次确认")
            engine.tap(cp.first, cp.second)
            waitClear(2000)
        }
        val t4 = System.currentTimeMillis()
        waitReady(2500)
        // 耗时拆解和 PC 版一样，卡在哪一段一眼能看到
        log("   （放弃 " + sec(t0, t4) + "s = 点击 " + sec(t0, t1) +
            " + 等弹窗 " + sec(t1, t2) + " + 确认 " + sec(t2, t3) +
            " + 等清空 " + sec(t3, t4) + "）")
    }

    private fun waitModal(ms: Long): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end && !engine.isStopped()) {
            val bmp = shot()
            if (bmp != null) {
                val hit = isModal(bmp)
                bmp.recycle()
                if (hit) return true
            }
            sleep(40)
        }
        return false
    }

    /** 等画面回到正常抽牌页：徽章读得出来、也没有弹窗。 */
    private fun waitReady(ms: Long): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end && !engine.isStopped()) {
            val bmp = shot()
            if (bmp != null) {
                val ok = !isModal(bmp) && Badge.read(bmp) != 0
                bmp.recycle()
                if (ok) return true
            }
            sleep(80)
        }
        return false
    }

    private fun waitClear(ms: Long): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end && !engine.isStopped()) {
            val bmp = shot()
            if (bmp != null) {
                val modal = isModal(bmp)
                val empty = !modal && CardReader.readCards(bmp).all { it.point == 0 }
                bmp.recycle()
                if (empty) return true
            }
            sleep(60)
        }
        return false
    }

    /** 确认按钮是亮黄色，弹窗一出来就能靠它认。 */
    private fun isModal(bmp: Bitmap): Boolean {
        val fit = Fit(bmp.width, bmp.height)
        val x = fit.px(1638f).coerceIn(0, bmp.width - 1)
        val y = fit.py(870f).coerceIn(0, bmp.height - 1)
        val c = bmp.getPixel(x, y)
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return r > 200 && g > 200 && b < 80
    }

    private fun shot(): Bitmap? = engine.frame()

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // ------------------------------------------------------------ 日志写法

    /** 动作行，和 PC 版一样：`   -> 抽牌 @(2381, 1086)`。 */
    private fun act(what: String, x: Int, y: Int) {
        log("   -> " + what + " @(" + x + ", " + y + ")")
    }

    /** 一手牌，和 PC 版同一个写法：[(5, '筒'), (5, '索')]。 */
    private fun cardsText(cards: List<Card>): String =
        cards.joinToString(", ", "[", "]") { c ->
            val suit = if (c.suit in 1..3) Target.SUIT_CN[c.suit] else "?"
            "(" + c.point + ", '" + suit + "')"
        }

    /** 两段时刻之间隔了多久，秒、留两位。 */
    private fun sec(from: Long, to: Long): String = f((to - from) / 1000.0, 2)

    /** 固定按 US 格式化，免得某些机型把小数点写成逗号。 */
    private fun f(x: Double, digits: Int): String =
        String.format(Locale.US, "%." + digits + "f", x)

    // ------------------------------------------------------------ 快速模式

    /**
     * 对着抽牌按钮连点，把 5 张一口气铺满，中间不读牌。
     *
     * 游戏在抽牌冷却期会把多出来的点击直接丢掉，不会排队，所以不用等按钮变亮，
     * 狂点就行；省掉的是「每张牌都要等渲染再认一遍」的开销。
     * 代价是做不了提前剪枝，而且必然是 5 张牌 = 5 个敌人。
     */
    private fun burst() {
        val start = System.currentTimeMillis()
        val end = start + BURST_MS
        var nextCheck = start + BURST_CHECK
        var sent = 0
        val p = Geom.tapDraw()
        act("快速连点 " + f(BURST_MS / 1000.0, 1) + "s", p.first, p.second)
        var full = false
        var bail = false
        while (System.currentTimeMillis() < end && !engine.isStopped()) {
            engine.tap(p.first, p.second)
            sent++
            sleep(BURST_GAP)
            if (System.currentTimeMillis() >= nextCheck) {
                nextCheck += BURST_CHECK
                val n = filledCount()
                if (n >= 5) {
                    full = true
                    break
                }
                // 一张都没抽出来、还立着确认弹窗：放弃没生效，交回主循环去确认
                if (n == 0 && modalNow()) {
                    bail = true
                    break
                }
            }
        }
        drawTaps += sent
        log("      连点 " + sent + " 次 / 用时 " +
            f((System.currentTimeMillis() - start) / 1000.0, 1) + "s")
        if (bail) {
            log("   [!] 放弃没生效，先回去把弹窗确认掉")
            return
        }
        if (full) return
        // 被急停打断时不算失败，不用报
        if (!engine.isStopped() && !waitFilled(1500)) {
            log("   [!] 只铺到 " + filledCount() + " 张（预期 5），按现有手牌判定")
        }
    }

    /** 快速模式的放弃：确认完就走，不等牌淡出——下一轮连点会把过渡期吃掉。 */
    private fun forfeitFast() {
        val fp = Geom.tapForfeit()
        act("放弃挑战", fp.first, fp.second)
        engine.tap(fp.first, fp.second)
        if (!waitModal(1500)) {
            log("   [!] 没等到放弃确认弹窗，这一下可能点空了")
            return
        }
        sleep(150)
        val cp = Geom.tapConfirm()
        act("确认放弃", cp.first, cp.second)
        engine.tap(cp.first, cp.second)
        // 确认这一下偶尔会被落位动画吞掉，弹窗留着的话后面连点就全打在它上面
        if (waitModal(200)) {
            sleep(80)
            engine.tap(cp.first, cp.second)
        }
    }

    /** 此刻屏幕上有放弃确认弹窗吗。 */
    private fun modalNow(): Boolean {
        val bmp = shot() ?: return false
        val m = isModal(bmp)
        bmp.recycle()
        return m
    }

    private fun filledCount(): Int {
        val bmp = shot() ?: return 0
        val n = CardReader.readCards(bmp).count { it.point > 0 }
        bmp.recycle()
        return n
    }

    private fun waitFilled(ms: Long): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end && !engine.isStopped()) {
            if (filledCount() >= 5) return true
            sleep(120)
        }
        return false
    }

    private companion object {
        /**
         * 连点的最长时长。5 张受游戏冷却限制，实测 5.5 秒左右铺满，
         * 但放弃后的过场会白白吃掉开头约 1 秒，所以给到 8 秒；
         * 铺满了会在 500ms 的探测点提前收手，所以放宽不花时间。
         */
        const val BURST_MS = 8000L

        /** 连点间隔：手势本身 40ms，加这点间隔一秒十来下。 */
        const val BURST_GAP = 55L

        /** 连点过程中每隔这么久瞄一眼牌面，铺满就提前收手。 */
        const val BURST_CHECK = 500L
    }
}

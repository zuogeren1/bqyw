package io.github.zuogeren.bqyw

import android.graphics.Bitmap

/**
 * 抽牌状态机：读牌 → 命中就停 → 没命中就放弃重开。
 *
 * 全部通过 Engine 接口跟外界打交道，方便以后换数据源或写测试。
 */
class Bot(
    private val engine: Engine,
    private val target: Target,
    private val maxAttempts: Int,
    private val fast: Boolean = false,
    private val log: (String) -> Unit,
) {

    interface Engine {
        fun frame(): Bitmap?
        fun tap(x: Int, y: Int): Boolean
        fun beep()
        fun isStopped(): Boolean
        fun saveDebug(bmp: Bitmap, name: String)
    }

    fun run(): String {
        var attempts = 0
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
                if (noFrame % 20 == 0) log("拿不到画面（抓屏停了？）")
                if (noFrame > 200) {
                    log("长时间没有画面，停止")
                    return "noframe"
                }
                sleep(150)
                continue
            }
            noFrame = 0

            // 弹窗盖住了画面：先确认掉，这时候读什么都是错的（整块白）
            if (isModal(bmp)) {
                bmp.recycle()
                val cp = Geom.tapConfirm()
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
                if (noRound % 12 == 6) log("读不到轮次（" + Badge.lastInfo + "）")
                if (noRound > 40) {
                    log("一直读不到轮次，停止")
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
                log("当前第 " + rnd + " 轮（" + Badge.lastInfo + "），已停止")
                return "round2"
            }
            badRound = 0

            val cards = CardReader.readCards(bmp)
            bmp.recycle()
            val pts = cards.map { it.point }.toIntArray()
            val filled = pts.count { it > 0 }
            if (filled > 0 && System.currentTimeMillis() < calmUntil) {
                sleep(120)
                continue
            }
            if (filled < 5) {
                if (filled == 0) {
                    idle++
                    lastFilled = 0
                    noGain = 0
                    if (idle > 40) {
                        log("牌桌一直是空的，停止")
                        return "stuck"
                    }
                } else {
                    idle = 0
                    // 连着抽几次都没多出一张：多半是有张牌没认出来，别一直空点下去
                    if (filled <= lastFilled) {
                        noGain++
                        if (noGain >= (if (fast) 3 else 8)) {
                            log("连续 " + noGain + " 次抽牌都没多出一张，停止")
                            return "stuck_draw"
                        }
                    } else {
                        noGain = 0
                    }
                    lastFilled = filled
                    // 提前剪枝：剩下这几抽怎么都凑不出目标了，直接重开，别浪费时间
                    // （快速模式刻意不做，用剪枝换吞吐）
                    val prefix = cards.takeWhile { it.point > 0 }
                    if (!fast && prefix.size == filled && !target.possible(prefix, 5 - filled)) {
                        log("  " + target + " 已不可能（" + filled + " 张），提前放弃")
                        forfeit()
                        calmUntil = System.currentTimeMillis() + 1500
                        continue
                    }
                }
                if (fast) {
                    burst()
                } else {
                    val p = Geom.tapDraw()
                    engine.tap(p.first, p.second)
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
            val hand = Hands.best(cards)
            log(
                "第 " + attempts + " 轮：" + pts.joinToString(" ") +
                    "  " + hand.cn + " ×" + hand.mult +
                    "  总战力 " + pts.sum(),
            )
            if (target.matches(cards)) {
                log("命中 " + target + "，预估奖励点 " + Hands.score(cards))
                engine.beep()
                return "hit"
            }
            if (maxAttempts > 0 && attempts >= maxAttempts) {
                log("达到重开上限 " + maxAttempts + "，停止")
                return "max"
            }
            if (fast) {
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

    private fun forfeit() {
        val fp = Geom.tapForfeit()
        engine.tap(fp.first, fp.second)
        if (!waitModal(1500)) {
            log("  没等到确认弹窗")
            return
        }
        // 黄色的确认键刚弹出来时还在落位动画里，这一下会被吞掉
        sleep(150)
        val cp = Geom.tapConfirm()
        engine.tap(cp.first, cp.second)
        if (!waitClear(3000)) {
            log("  确认后牌桌没清空，补点一次确认")
            engine.tap(cp.first, cp.second)
            waitClear(2000)
        }
        waitReady(2500)
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

    // ------------------------------------------------------------ 快速模式

    /**
     * 对着抽牌按钮连点，把 5 张一口气铺满，中间不读牌。
     *
     * 游戏在抽牌冷却期会把多出来的点击直接丢掉，不会排队，所以不用等按钮变亮，
     * 狂点就行；省掉的是「每张牌都要等渲染再认一遍」的开销。
     * 代价是做不了提前剪枝，而且必然是 5 张牌 = 5 个敌人。
     */
    private fun burst() {
        val end = System.currentTimeMillis() + BURST_MS
        var nextCheck = System.currentTimeMillis() + BURST_CHECK
        while (System.currentTimeMillis() < end && !engine.isStopped()) {
            val p = Geom.tapDraw()
            engine.tap(p.first, p.second)
            sleep(BURST_GAP)
            if (System.currentTimeMillis() >= nextCheck) {
                nextCheck += BURST_CHECK
                val n = filledCount()
                if (n >= 5) return
                // 一张都没抽出来、还立着确认弹窗：放弃没生效，交回主循环去确认
                if (n == 0 && modalNow()) {
                    log("  放弃没生效，先回去把弹窗确认掉")
                    return
                }
            }
        }
        // 被急停打断时不算失败，不用报
        if (!engine.isStopped() && !waitFilled(1500)) {
            log("  连点没把牌铺满，按现有手牌判定")
        }
    }

    /** 快速模式的放弃：确认完就走，不等牌淡出——下一轮连点会把过渡期吃掉。 */
    private fun forfeitFast() {
        val fp = Geom.tapForfeit()
        engine.tap(fp.first, fp.second)
        if (!waitModal(1500)) {
            log("  没等到确认弹窗")
            return
        }
        sleep(150)
        val cp = Geom.tapConfirm()
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

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
                sleep(300)
                continue
            }

            val rnd = Badge.read(bmp)
            if (rnd == 0) {
                bmp.recycle()
                noRound++
                if (noRound % 12 == 1) log("读不到轮次（" + Badge.lastInfo + "）")
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
            if (filled < 5) {
                if (filled == 0) {
                    idle++
                    if (idle > 40) {
                        log("牌桌一直是空的，停止")
                        return "stuck"
                    }
                } else {
                    idle = 0
                }
                val p = Geom.tapDraw()
                engine.tap(p.first, p.second)
                sleep(850)
                continue
            }
            idle = 0
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
            forfeit()
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
        val x = (1638f * bmp.width / 2800f).toInt().coerceIn(0, bmp.width - 1)
        val y = (870f * bmp.width / 2800f).toInt().coerceIn(0, bmp.height - 1)
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
}

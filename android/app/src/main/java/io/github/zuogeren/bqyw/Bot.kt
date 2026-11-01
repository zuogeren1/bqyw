package io.github.zuogeren.bqyw

import android.graphics.Bitmap
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 抽牌状态机：读牌 → 命中就停 → 没命中就放弃重开。
 *
 * 全部通过 Engine 接口跟外界打交道，方便以后换数据源或写测试。
 */
class Bot(
    private val engine: Engine,
    private val target: TargetBase,
    private val maxAttempts: Int,
    /** 敌人名单；关着时是 [EnemyPolicy.OFF]，完全不碰模板。 */
    private val enemy: EnemyPolicy = EnemyPolicy(),
    /** 敌人模板库；名单开着才由 CaptureService 建好传进来。 */
    private val book: Enemies.Book? = null,
    private val log: (String) -> Unit,
) {

    /** 已经读到手牌的有几轮。 */
    private var attempts = 0

    /** 累计翻出来的牌数、点了多少下「抽牌」，结束摘要用。 */
    private var cardsDrawn = 0
    private var drawTaps = 0

    /** 上一帧的已抽张数，用来算这一帧多翻了几张。 */
    private var prevCards = 0

    private var startedAt = 0L

    // ------------------------------------------------------------ 点击节拍
    //
    // 游戏抽牌有冷却（实测 ≈1.38s）。三种错法都踩过：
    //   * 点一下干等 2.5s —— 点空时白等一整个周期；
    //   * 补点间隔贴着冷却（1.15s）—— 大半点击落在冷却里，两三下才出一张牌；
    //   * 每出一张就 −0.02 慢慢回收 —— 一路滑到 1.25，每隔十来张点空一次，来回抵消。
    // 所以这里是「固定节拍 + 点空加码 + 出牌回收但不低于下限」，跨轮保持。

    /** 两次「抽牌」之间的间隔，秒。初值就是真机验过的值。 */
    private var clickDelay = CLICK_DELAY

    /** 上一次点击的时刻。 */
    private var lastTapAt = 0L

    /** 上一次点击还没换来新牌（用来判这一下有没有点空）。 */
    private var tapPending = false

    /** 上一次点击那一刻牌桌上已有几张牌。 */
    private var tapPendingFilled = 0

    /** 判定点空了几次，用来控制日志频次。 */
    private var dropCount = 0

    /** 真正放弃过几次。「轮」只统计判满 5 张的局，早期放弃的局不记轮，所以放弃耗时得按它平摊。 */
    private var forfeitCount = 0

    /** 耗时统计，每 10 轮打一行 [节奏]。 */
    private var readMs = 0L
    private var tapMs = 0L
    private var waitMs = 0L
    private var forfeitMs = 0L

    // ------------------------------------------------------------ 敌人名单
    //
    // 判定点就在每次抽牌之后：认的是立绘，命中黑名单（或不在白名单）就放弃重开。
    // **不睡、不额外抓屏**：主循环本来就抓了这一帧，认牌只用同一份像素；
    // 认不清的记进 enemyPending，等下一张牌翻出来时顺带一起认 —— 那时翻牌动画
    // 也早走完了，分数只会更高。所以识别对节拍零影响。
    //
    // **一眼不够清楚的两帧定案**：≥ CONFIRM_NCC 直接弃；0.78~0.88 先记进
    // enemyConfirm，下一帧还是同一个敌人才弃 —— 这一段多半是半张立绘，宁可晚弃一张。
    // 认不出就当没这个敌人：认不出不弃，绝不乱弃。

    /** 这一轮已经认完的槽位。按槽位记而不是记个数：中间有张牌没认出来也照样补认。 */
    private val enemyDone = BooleanArray(5)

    /** 没认出来的牌：槽位 → 目前最高分。 */
    private val enemyPending = HashMap<Int, Float>()

    /** 看着像、但分数不够硬的：槽位 → (敌人名字, 分数)。 */
    private val enemyConfirm = HashMap<Int, Pair<String, Float>>()

    /** 上一帧有牌的张数，用来判断牌桌是不是被清空过。 */
    private var enemyChecked = 0

    /** 最近一次命中名单时的立绘分数（写日志用）。 */
    private var enemyHitScore = 0f

    /** 连续因为名单放弃了几轮。连着撞满 MAX_DROPS 就停手。 */
    private var enemyStreak = 0

    /** 累计因为名单放弃的轮数，写进结束摘要。 */
    private var enemyDrops = 0

    interface Engine {
        fun frame(): Bitmap?
        fun tap(x: Int, y: Int): Boolean
        fun beep()
        fun isStopped(): Boolean
        fun saveDebug(bmp: Bitmap, name: String)
    }

    fun run(): String {
        startedAt = System.currentTimeMillis()
        try {
            return runLoop()
        } finally {
            // 短跑也要看得到账：正常节奏行每 10 轮才有一次，退出前补一条（没翻出牌就不写）
            if (cardsDrawn > 0) logPace()
        }
    }

    private fun runLoop(): String {
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
            val tShot = System.nanoTime()
            val bmp = shot()
            readMs += msSince(tShot)
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

            val tBadge = System.nanoTime()
            val rnd = Badge.read(bmp)
            readMs += msSince(tBadge)
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

            val tCards = System.nanoTime()
            val cards = CardReader.readCards(bmp)
            readMs += msSince(tCards)
            val pts = cards.map { it.point }.toIntArray()
            val filled = pts.count { it > 0 }
            // 刚放弃完的一小段时间里，牌桌上的牌可能是正在淡出的旧牌，读到不算数
            val fading = filled > 0 && System.currentTimeMillis() < calmUntil
            // 敌人名单：就用这一帧认，不睡也不额外抓屏（淡出中的旧牌不认）
            var banned: String? = null
            if (!fading) {
                val tEnemy = System.nanoTime()
                banned = checkEnemies(bmp, cards, filled)
                readMs += msSince(tEnemy)
            }
            bmp.recycle()
            // 只统计真翻出来的牌（不是点击次数），结束摘要里的「张牌」按这个算
            if (filled > prevCards) cardsDrawn += filled - prevCards
            prevCards = filled
            // 点击节拍：多出一张就慢慢回收，点空了就往上抬（跨轮保持，别每轮重置）
            updateBeat(filled)
            if (fading) {
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
            // ---- 敌人名单命中：立刻放弃重开，不等抽满、也不等牌型判完
            if (banned != null) {
                enemyDrops++
                enemyStreak++
                val what = if (enemy.mode == Enemies.MODE_ONLY) "不在白名单" else "命中黑名单"
                log("     " + what + "「" + banned + "」（立绘 " +
                    f(enemyHitScore.toDouble(), 2) + "），放弃重开")
                if (enemyStreak >= Enemies.MAX_DROPS) {
                    log("\n[!] 连着 " + enemyStreak + " 轮都撞名单，大概率是名单配得太宽，已停止。")
                    return "enemy_ban"
                }
                flushEnemyPending()
                if (roundStillOne()) {
                    forfeit()
                } else {
                    // 第二轮按放弃等于退出整场演算，读不准就再等一圈
                    log("   [!] 轮次复核没通过，先不放弃")
                }
                calmUntil = System.currentTimeMillis() + 1500
                continue
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
                        if (noGain >= 8) {
                            log("   [!] 连续 " + noGain + " 次抽牌都没多出一张，停止")
                            return "stuck_draw"
                        }
                    } else {
                        noGain = 0
                    }
                    lastFilled = filled
                    // 提前剪枝：剩下这几抽怎么都凑不出目标了，直接重开，别浪费时间
                    if (intact && !target.possible(drawn, left)) {
                        log("     " + target + " 已不可能，提前放弃")
                        if (!roundStillOne()) {
                            // 第二轮按放弃等于退出整场演算，读不准就再等一圈
                            log("   [!] 轮次复核没通过，先不放弃")
                            calmUntil = System.currentTimeMillis() + 1500
                            continue
                        }
                        forfeit()
                        calmUntil = System.currentTimeMillis() + 1500
                        continue
                    }
                }
                // 先等够节拍，再用「这一刻新抓的一帧」判上一拍有没有点空：
                // 此刻距上次点击已满一个完整节拍，牌该出来的一定出来了，云端流延迟也吃得住
                waitTapSlot()
                val nowFilled = filledNow()
                if (nowFilled != null && tapPending && nowFilled <= tapPendingFilled) {
                    tapPending = false
                    dropCount++
                    clickDelay = minOf(CLICK_DELAY_MAX, clickDelay + CLICK_DELAY_STEP)
                    // 前 3 次和之后每 10 次写一行，别刷屏
                    if (dropCount <= 3 || dropCount % 10 == 0) {
                        log("   [!] 这一下点空了，点击节拍调到 " + f(clickDelay, 2) + "s")
                    }
                }
                // 抽牌：点完记一笔，好判这一下有没有点空
                val p = Geom.tapDraw()
                act("抽牌", p.first, p.second)
                // 节拍从「派发点击」算起：tap 本身要等手势回调或 shell，别让它吃掉节拍
                lastTapAt = System.currentTimeMillis()
                val tTap = System.nanoTime()
                engine.tap(p.first, p.second)
                tapMs += msSince(tTap)
                drawTaps++
                tapPending = true
                // 基线用「这次点击前刚读到的张数」，不是循环开头那帧的旧值
                tapPendingFilled = nowFilled ?: filled
                // 别盲等太久：云端一张牌从点下到翻出来约 1 秒，盲等 850ms 会正好错过，
                // 于是"上一张"要等下一圈才看到，实测单张被拖到 2.0 秒。
                // 只等 300ms 就回去读，牌一出现立刻进入判定；多抓两帧的代价远小于等一整圈。
                sleep(300)
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
            // 抽满这一轮都没撞名单，连着撞的计数清零
            enemyStreak = 0
            attempts++
            // 短自定义目标命中时手上还不到 5 张，牌型也只能按实际抽到的牌算
            val hand = Hands.best(drawn)
            log("[第 " + attempts + " 轮]")
            log(
                "   [" + drawn.size + "张] " + cardsText(drawn) +
                    " 牌型=" + hand.cn + " 战力点=" + pts.sum(),
            )
            if (attempts % 10 == 0) logPace()
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
            if (!roundStillOne()) {
                // 第二轮按放弃等于退出整场演算，读不准就再等一圈
                log("   [!] 轮次复核没通过，先不放弃")
                sleep(400)
                continue
            }
            forfeit()
            calmUntil = System.currentTimeMillis() + 1500
        }
        return "stopped"
    }

    /**
     * 结束摘要，写法和 PC 版一致：
     * 用时 602s，105 轮，平均 5.7s/轮，587 张牌，1.02s/张（点击 1.22 次/张），点击节拍 1.45 s。
     * 一张牌都没翻出来时中间几项整项不写 —— 算不出来的不编。
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
        sb.append("，点击节拍 ").append(f(clickDelay, 2)).append(" s")
        if (enemyDrops > 0) {
            sb.append("，因名单弃 ").append(enemyDrops).append(" 轮")
        }
        return sb.toString()
    }

    // ------------------------------------------------------------ 敌人名单

    /**
     * 把这一帧的牌对一遍敌人名单；确认命中就返回敌人名字。
     *
     * [filled] 是这一帧翻开的张数（CardReader 永远给 5 格，没翻开的是 point = 0）。
     * 认不清的记进 enemyPending，等下一张牌翻出来、主循环本来就抓了新帧时顺带一起认；
     * 一帧只比一次，算的是同一份像素，所以识别对节拍零影响。
     */
    private fun checkEnemies(bmp: Bitmap, cards: List<Card>, filled: Int): String? {
        val bk = book ?: return null
        if (!enemy.active()) return null
        if (filled < enemyChecked) {
            // 牌桌被清空过：上一轮没认出的作废
            flushEnemyPending()
            enemyChecked = 0
        }
        // 这一帧要认的：新翻出来的 + 没认清的 + 看着像、等确认的
        val todo = ArrayList<Int>(5)
        for (i in 0 until minOf(filled, cards.size)) {
            if (!enemyDone[i]) todo.add(i)
        }
        for (i in enemyPending.keys.sorted()) {
            if (i !in todo) todo.add(i)
        }
        for (i in enemyConfirm.keys.sorted()) {
            if (i !in todo) todo.add(i)
        }
        enemyChecked = filled
        for (i in todo) {
            if (i >= cards.size) continue
            val point = cards[i].point
            if (point <= 0) {
                // 这张还没翻开：等下一帧
                enemyPending.remove(i)
                enemyConfirm.remove(i)
                continue
            }
            val hit = bk.best(bmp, i, point)
            val score = hit?.score ?: 0f
            if (hit == null || score < bk.threshold) {
                // 分数留着，只涨不跌：下一帧是更晚的画面，认得更清
                enemyDone[i] = false
                enemyPending[i] = maxOf(score, enemyPending[i] ?: 0f)
                enemyConfirm.remove(i)
                continue
            }
            enemyDone[i] = true
            enemyPending.remove(i)
            if (!enemy.shouldDrop(hit.name)) {
                // 认出来了但不在名单里，不用再确认
                enemyConfirm.remove(i)
                continue
            }
            if (score >= Enemies.CONFIRM_NCC) {
                // 一眼就够清楚
                enemyHitScore = score
                return hit.name
            }
            val seen = enemyConfirm[i]
            if (seen != null && seen.first == hit.name) {
                // 下一帧还是同一个敌人 → 确认
                enemyHitScore = score
                return hit.name
            }
            // 先记下，下一帧再定
            enemyConfirm[i] = hit.name to score
        }
        return null
    }

    /** 一轮结束：还没认出来的牌各记一行日志，本轮的认牌账清零。 */
    private fun flushEnemyPending() {
        for (i in enemyPending.keys.sorted()) {
            log(
                "     第 " + (i + 1) + " 张一直没认出来（最像 " +
                    f(enemyPending.getValue(i).toDouble(), 2) + "）",
            )
        }
        enemyPending.clear()
        enemyConfirm.clear()
        enemyDone.fill(false)
    }

    // ------------------------------------------------------------ 节拍

    /** 等到能点下一张：距上次点击不足 [clickDelay] 就分段睡，急停能马上退出来。 */
    private fun waitTapSlot() {
        val from = System.currentTimeMillis()
        while (!engine.isStopped()) {
            val left = clickDelay * 1000.0 - (System.currentTimeMillis() - lastTapAt)
            if (left <= 0.0) break
            sleep(minOf(left, 50.0).toLong().coerceAtLeast(1L))
        }
        waitMs += System.currentTimeMillis() - from
    }

    /**
     * 出牌了就慢慢回收节拍（不低于 1.40s）。
     *
     * 点空的判定放在「下一次点击之前」做，而且还用那一刻新抓的一帧，见抽牌分支：
     * 安卓一帧是 850ms 打底再加截屏和认牌，云端还有流延迟，牌常常是下一帧才出现 ——
     * 按「多久没出牌」判会被下一张牌盖掉，空点永远查不出来。
     *
     * 回收保下限是关键：贴着冷却走的话大半点击落在冷却里，只能靠「每隔十来张
     * 点空一次」抵消回来，等于白折腾。
     */
    private fun updateBeat(filled: Int) {
        if (filled > tapPendingFilled) {
            clickDelay = maxOf(CLICK_DELAY_MIN, clickDelay - CLICK_RECOVER)
            // 出牌了就把这笔账结掉，否则下一拍会被误判成点空
            tapPending = false
        }
    }

    /**
     * 放弃前重新截一帧再读一次徽章。
     *
     * 第二轮按「放弃挑战」等于退出整场演算，一次误读代价太大，宁可多抓一帧。
     */
    private fun roundStillOne(): Boolean {
        val bmp = shot() ?: return false
        return try {
            Badge.read(bmp) == 1
        } finally {
            bmp.recycle()
        }
    }

    /** 每 10 轮打一行节奏账：等节拍 / 点击 / 读牌 / 放弃各花了多少。 */
    private fun logPace() {
        val n = cardsDrawn.coerceAtLeast(1)
        val r = forfeitCount.coerceAtLeast(1)
        log(
            "[节奏] " + cardsDrawn + " 张 / " + attempts + " 轮：" +
                "等节拍 " + f(waitMs / 1000.0 / n, 2) + "s/张，" +
                "点击 " + f(tapMs / 1000.0 / n, 2) + "，" +
                "读牌 " + f(readMs / 1000.0 / n, 2) + "，" +
                "放弃 " + f(forfeitMs / 1000.0 / r, 2) + "s/轮，" +
                "点击节拍 " + f(clickDelay, 2) + "s",
        )
    }

    // ------------------------------------------------------------ 放弃

    private fun forfeit() {
        val t0 = System.currentTimeMillis()
        val fp = Geom.tapForfeit()
        act("放弃挑战", fp.first, fp.second)
        engine.tap(fp.first, fp.second)
        val t1 = System.currentTimeMillis()
        if (!waitModal(1500)) {
            forfeitMs += System.currentTimeMillis() - t0
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
        forfeitMs += t4 - t0
        forfeitCount++
        // 牌桌是我们自己清空的：上一拍的"账"到此作废。
        // 不清的话，下一拍会拿"清空后的 0 张"去比"清空前记的 N 张"，必然判成点空，
        // 节拍每轮白涨 0.15s（实测 1.45 → 1.58 → 1.73 → 1.88，单张被拖到 2.07s）。
        tapPending = false
        tapPendingFilled = 0
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

    /**
     * 放弃确认弹窗：黄色的确认键一出来就能靠它认。
     *
     * 原来是硬采样 (1638,870) 一个点（基准坐标）。**但弹窗自身会不会跟着边距挪没实测过**
     * （两张边距实测图里都没有弹窗）：真挪了那一点就落到按钮外面，弹窗认不出来，
     * 后面的等待 / 确认 / 补点全乱。所以改成沿 y=870 在 x∈[1400,1900] 里横扫，
     * 只要有一段连续够黄就算弹窗在 —— 弹窗动不动都不影响判定：
     *
     *  * 确认键本身 433 px 宽（实测 x 1433…1866），边距最多把界面推 351 px，
     *    无论左移还是右移，这段窗口里都还剩 80 px 以上的黄条；
     *  * 边距=0 时更简单：黄条就压在原来的那一点上，行为和旧版完全一致；
     *  * 这一行（y=870）在正常抽牌界面上没有黄色：refs 和 .notes 里上百张历史截图
     *    逐张按同一判据跑过，非弹窗的全是 0，四张弹窗截图全中（黄条 109~276 px 长）。
     */
    private fun isModal(bmp: Bitmap): Boolean {
        val fit = Fit(bmp.width, bmp.height)
        val y = fit.py(MODAL_Y)
        if (y < 0 || y >= bmp.height) return false
        val xa = fit.px(MODAL_X0).coerceIn(0, bmp.width - 1)
        val xb = fit.px(MODAL_X1).coerceIn(xa, bmp.width - 1)
        val n = xb - xa + 1
        val row = IntArray(n)
        try {
            bmp.getPixels(row, 0, n, xa, y, n, 1)
        } catch (e: Throwable) {
            // 画面被裁过 / 取不到这一行：宁可当没弹窗，别把正常画面当成弹窗反复确认
            return false
        }
        // 采样步长跟着缩放走；「够黄的一段」仍按屏幕像素算
        val step = (2f * fit.s).roundToInt().coerceAtLeast(1)
        var run = 0
        var i = 0
        while (i < n) {
            if (isConfirmYellow(row[i])) {
                run += step
                if (run >= MODAL_RUN) return true
            } else {
                run = 0
            }
            i += step
        }
        return false
    }

    /** 确认键的亮黄色（和原来的单点判据完全一致，只是现在扫一整行）。 */
    private fun isConfirmYellow(c: Int): Boolean {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return r > 200 && g > 200 && b < 80
    }

    private fun shot(): Bitmap? = engine.frame()

    /** 抓一帧数翻开的张数；抓不到画面就返回 null，调用方跳过判定、别记点空。 */
    private fun filledNow(): Int? {
        val t0 = System.nanoTime()
        val bmp = shot() ?: return null
        return try {
            CardReader.readCards(bmp).count { it.point > 0 }
        } finally {
            bmp.recycle()
            readMs += msSince(t0)
        }
    }

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

    /** 从 [fromNanos] 到现在，毫秒。 */
    private fun msSince(fromNanos: Long): Long = (System.nanoTime() - fromNanos) / 1_000_000

    /** 固定按 US 格式化，免得某些机型把小数点写成逗号。 */
    private fun f(x: Double, digits: Int): String =
        String.format(Locale.US, "%." + digits + "f", x)

    private companion object {
        /** 两次点击的间隔（秒）：冷却实测 ≈1.38s，留一点余量。 */
        const val CLICK_DELAY = 1.45

        /** 点空一次往上抬多少、抬到多少封顶。 */
        const val CLICK_DELAY_STEP = 0.15
        const val CLICK_DELAY_MAX = 2.30

        /** 每多出一张牌回落多少，但绝不低于这个下限（贴回 1.25 会一路点空）。 */
        const val CLICK_RECOVER = 0.01
        const val CLICK_DELAY_MIN = 1.40

        /** 弹窗确认键落在这一行上（基准坐标）。原来只认 (1638,870) 一个点，见 [isModal]。 */
        const val MODAL_Y = 870f

        /** 横扫的左右边界（基准坐标）：比确认键 433 px 的宽度还宽，够容边距把它推到任一侧。 */
        const val MODAL_X0 = 1400f
        const val MODAL_X1 = 1900f

        /** 多长一段连续黄才算弹窗（屏幕像素）：确认键本身 433 px 宽，20 px 留足余量。 */
        const val MODAL_RUN = 20
    }
}

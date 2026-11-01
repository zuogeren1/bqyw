package io.github.zuogeren.bqyw

/**
 * 轮数记账：**一次发牌 = 一轮**（和 PC 版同一个口径）。
 *
 * 只认「牌桌从空变成有牌」那一下：见到牌就记一轮并上锁，牌桌清空后解锁，
 * 下一次有牌才算新一轮 —— 就是 PC 版 `Bot.new_round()` 里那个 `_armed` 的写法。
 * 跟这一轮最后抽了几张**无关**：2 张就判「已不可能」提前放弃的局，照样算一轮。
 *
 * 为什么专门拎出来：1.2.0 之前是在「读到 5 张满手牌」那个分支里 `attempts++`，
 * 提前放弃的局一次都没记过账，真机日志里出现过「5 张 / 0 轮，平均 10.9s/轮」
 * 这种假数。改成按发牌记账之后，两种局都算一轮。
 *
 * 刻意做成纯类（不碰 Android API），主循环的记账逻辑能用 JVM 单测直接跑。
 */
class Rounds(private val limit: Int = 0) {

    /** 已经开了几轮。 */
    var started = 0
        private set

    /** 这一轮记过账了没。牌桌清空就解锁（下一张牌开始算新一轮）。 */
    var armed = false
        private set

    /** 到上限了没。[limit] <= 0 表示不限。 */
    val exhausted: Boolean get() = limit > 0 && started >= limit

    /**
     * 每帧喂一次「牌桌上有几张牌」。
     *
     * @return 这一帧开了新一轮就是它的轮号（> 0，调用方在这里打 `[第 N 轮]`）；
     *         不是新一轮就返回 0 —— 还在同一轮里，或者牌桌是空的。
     *
     * 到上限之后不再往上加：此时返回 0、[armed] 仍是 false，调用方据此判断
     * 「想开新一轮但开不了」→ 停手（和 PC 一样，在开下一轮之前停）。
     */
    fun tick(filled: Int): Int {
        if (filled <= 0) {
            // 牌桌清空了：下一张牌才算新一轮
            armed = false
            return 0
        }
        if (armed) return 0
        if (exhausted) return 0
        started++
        armed = true
        return started
    }
}

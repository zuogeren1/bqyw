package io.github.zuogeren.bqyw

/**
 * 点按的统一入口。
 *
 * 两种方式，无障碍优先、Shizuku 兜底：
 *  - 无障碍：进程内 dispatchGesture，不用装 Shizuku，最省事
 *  - Shizuku：shell 身份的 `input tap`，无障碍没法用时还能用
 *
 * 一次点不出去会立刻换另一种再试一次，免得一轮牌卡死在那里。
 */
object Tap {

    const val A11Y = "无障碍"
    const val SHELL = "Shizuku"
    const val NONE = "无"

    fun a11yReady(): Boolean = TapService.isReady()

    fun shellReady(): Boolean = ShizukuLink.ready()

    fun ready(): Boolean = a11yReady() || shellReady()

    /** 当前会用哪种方式点，给界面显示用。 */
    fun current(): String = when {
        a11yReady() -> A11Y
        shellReady() -> SHELL
        else -> NONE
    }

    fun tap(x: Int, y: Int): Boolean {
        if (a11yReady() && TapService.instance?.tapAt(x, y) == true) return true
        if (shellReady() && ShizukuLink.tap(x, y)) return true
        return false
    }
}

package io.github.zuogeren.bqyw

import android.content.Context

/**
 * 边距值的持久化：和敌人名单**共用一个 SharedPreferences 文件**（都是这台机器的配置），
 * 只多占一个 key。默认 0 = 不补偿 —— 老用户装上新版什么都不用改，行为一模一样。
 *
 * 读写在悬浮球改值和每次开跑时各做一次，改完立刻生效，不用重开 App。
 */
object MarginStore {

    /** 和 [EnemyStore] 同一个文件（那边是 "enemy_policy"）。改这里必须两边一起改。 */
    private const val FILE = "enemy_policy"

    private const val KEY_MARGIN = "margin"

    fun load(ctx: Context): Int {
        val v = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt(KEY_MARGIN, 0)
        return v.coerceIn(0, Geom.MARGIN_MAX)
    }

    fun save(ctx: Context, v: Int) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt(KEY_MARGIN, v.coerceIn(0, Geom.MARGIN_MAX))
            .apply()
    }
}

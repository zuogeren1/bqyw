package io.github.zuogeren.bqyw

import android.content.Context

/**
 * 自定义目标的持久化：和敌人名单、边距**共用一个 SharedPreferences 文件**。
 *
 * 存的是每组一行 spec（`"5 5同 5同"`），和 PC 版 config.json 里的写法完全一样，
 * 两端抄来抄去都能读懂。另外两个开关（自定义勾没勾、按不按顺序）单独存。
 */
object TargetStore {

    /** 和 [EnemyStore] / [MarginStore] 同一个文件。改这里必须两边一起改。 */
    private const val FILE = "enemy_policy"

    private const val KEY_SPECS = "custom_specs"
    private const val KEY_ORDERED = "custom_ordered"
    private const val KEY_ON = "custom_on"

    /** 存下来的一组状态：每组一段 spec + 两个开关。默认值等于 1.1.0 的老口径。 */
    data class Saved(
        val specs: List<String> = emptyList(),
        val ordered: Boolean = false,
        val on: Boolean = true,
    )

    fun load(ctx: Context): Saved {
        val sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val raw = sp.getString(KEY_SPECS, null) ?: return Saved()
        val specs = raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        return Saved(
            specs = specs,
            ordered = sp.getBoolean(KEY_ORDERED, false),
            on = sp.getBoolean(KEY_ON, true),
        )
    }

    fun save(ctx: Context, specs: List<String>, ordered: Boolean, on: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_SPECS, specs.joinToString("\n"))
            .putBoolean(KEY_ORDERED, ordered)
            .putBoolean(KEY_ON, on)
            .apply()
    }
}

package io.github.zuogeren.bqyw

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 边距换算的 JVM 单测（不碰 Android API）。
 *
 * 数据来源 .notes/margin-android.md：边距 130 实测内缩 151.96 px（k = 1.1689），
 * 纵向 dy 恒为 0，居中的控件逐像素不动。这里把三条硬结论钉住：
 *
 *  1. **边距 0 → 一个数都不许变**（老用户默认值，行为必须和 1.2.0 第一版一模一样）；
 *  2. 边距 130 → m ≈ 152：抽牌 / 放弃跟着左移，弹窗确认（居中）不动，徽章窗口跟着左移；
 *  3. 越界钳到 0~300，300 → m ≈ 351。
 *
 * 补偿全部在基准坐标（2800×1260）里算，再交给 [Fit] 缩放 —— 所以第 2 条里
 * 顺带验了一遍换分辨率的情形。
 */
class MarginTest {

    @After
    fun reset() {
        Geom.setMargin(0)
        Geom.setScreen(2800, 1260)
    }

    /** 边距 0：抽牌 / 放弃 / 确认 / 徽章窗口全部保持原值。 */
    @Test
    fun marginZeroChangesNothing() {
        Geom.setScreen(2800, 1260)
        Geom.setMargin(0)

        assertEquals("边距 0 不许有补偿", 0f, Geom.marginPx, 1e-4f)

        val d = Geom.tapDraw()
        assertEquals("抽牌 x", 2520L, d.first.toLong())
        assertEquals("抽牌 y", 885L, d.second.toLong())
        val f = Geom.tapForfeit()
        assertEquals("放弃 x", 1955L, f.first.toLong())
        assertEquals("放弃 y", 1140L, f.second.toLong())
        val c = Geom.tapConfirm()
        assertEquals("确认 x", 1638L, c.first.toLong())
        assertEquals("确认 y", 870L, c.second.toLong())

        // 轮次徽章窗口：边距 0 时就是标定值（宽度只有 38×60，差 10 px 就读不出）
        assertArrayEquals(
            intArrayOf(2624, 1015, 2662, 1075),
            Badge.window(2800, 1260),
        )
    }

    /** 边距 130：m = 130 × 1.169 ≈ 152，和实测表 §6.1 的新坐标表逐项对上。 */
    @Test
    fun margin130ShiftsAnchorsByMeasured151() {
        Geom.setScreen(2800, 1260)
        Geom.setMargin(130)

        assertEquals("m = 130 × 1.169", 151.97f, Geom.marginPx, 0.01f)

        // §6.1：DRAW 2520 → 2368、FORFEIT 1955 → 1803、CONFIRM 居中不动
        assertEquals("抽牌 2520 − 152", 2368L, Geom.tapDraw().first.toLong())
        assertEquals("抽牌 y 不动", 885L, Geom.tapDraw().second.toLong())
        assertEquals("放弃 1955 − 152", 1803L, Geom.tapForfeit().first.toLong())
        assertEquals("放弃 y 不动", 1140L, Geom.tapForfeit().second.toLong())
        assertEquals("确认 居中不动", 1638L, Geom.tapConfirm().first.toLong())

        // 左锚 / 居中也各验一格，和实测表 §2 对得上（顶部标题 60 → 212、战力点表头不动）
        assertEquals("左锚 60 + 152", 212L, Geom.lx(60f).toLong())
        assertEquals("居中 330 不动", 330L, Geom.cx(330f).toLong())

        // 徽章窗口：X0/X1 各 −152，Y 一行都不动
        assertArrayEquals(
            intArrayOf(2472, 1015, 2510, 1075),
            Badge.window(2800, 1260),
        )

        // 换分辨率：m 在基准坐标里算，屏幕坐标跟着 Fit 走，不是「减 152 个屏幕像素」
        Geom.setScreen(2400, 1080)
        val fit = Fit(2400, 1080)
        assertEquals(0f, fit.ox, 1e-3f)
        assertEquals(
            "屏幕坐标 = Fit(基准 2520 − m)",
            fit.px(2520f - Geom.marginPx).toLong(),
            Geom.tapDraw().first.toLong(),
        )
        assertEquals("2400×1080 上缩放是 0.857", 0.857f, fit.s, 0.001f)
    }

    /** 越界钳制：负数按 0、超过 300 按 300（300 × 1.169 ≈ 351）。 */
    @Test
    fun marginIsClampedTo300() {
        Geom.setScreen(2800, 1260)

        Geom.setMargin(-5)
        assertEquals("负数按 0", 0L, Geom.margin.toLong())
        assertEquals(0f, Geom.marginPx, 1e-4f)

        Geom.setMargin(9999)
        assertEquals("超过上限按 300", 300L, Geom.margin.toLong())
        assertEquals("300 × 1.169 ≈ 351", 350.7f, Geom.marginPx, 0.05f)

        Geom.setMargin(301)
        assertEquals("301 也要钳到 300", 300L, Geom.margin.toLong())

        // 300 时：抽牌 2520 − 350.7 = 2169.3 → 2169
        assertEquals("抽牌 2520 − 351", 2169L, Geom.tapDraw().first.toLong())
        // 徽章窗口还在画面里（2662 − 350.7 = 2311.3），不会跑到负坐标去
        val win = Badge.window(2800, 1260)
        assertEquals("徽章窗口左", 2273L, win[0].toLong())
        assertEquals("徽章窗口右", 2311L, win[2].toLong())
    }
}

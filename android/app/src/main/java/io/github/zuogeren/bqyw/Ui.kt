package io.github.zuogeren.bqyw

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 界面共用的那点东西：颜色、等宽字体、按下会变色的底、状态点、小标题、细线。
 *
 * 视觉和 PC 端 GUI、v1.2.0 宣传片是同一套：深色底、绿色强调（#2FBF71）、等宽数字、
 * 1px 细线、直角。全部用平台自带的 View / Drawable 画，不加图片资源、不引任何库，
 * APK 体积基本不动。
 *
 * 悬浮球面板和主界面都从这里取，免得两边各画一套、颜色慢慢跑偏。
 */
object Ui {

    // ------------------------------------------------------------ 颜色
    // 数值取自宣传片的 CSS 变量（--bg / --ink / --green-lt …），绿色按需求调亮到 #2FBF71

    /** 深色底。 */
    const val BG = 0xFF0E1412.toInt()

    /** 面板底（比背景亮一档）。 */
    const val PANEL = 0xFF121A17.toInt()

    /** 再亮一档，用在卡片 / 输入框底。 */
    const val PANEL2 = 0xFF16201C.toInt()

    /** 日志那种更黑一点的底。 */
    const val LOG_BG = 0xFF0A0F0D.toInt()

    /** 细线：rgba(232,230,224,.10)。 */
    const val LINE = 0x1AE8E6E0

    /** 粗一点的线：rgba(232,230,224,.18)。 */
    const val LINE2 = 0x2EE8E6E0

    /** 正文。 */
    const val INK = 0xFFE8E6E0.toInt()

    /** 次要文字。 */
    const val DIM = 0xFF7C8A84.toInt()

    /** 再次要一点。 */
    const val DIM2 = 0xFF8B9891.toInt()

    /** 强调绿：已授权、运行中、大数字。 */
    const val GREEN = 0xFF2FBF71.toInt()

    /** 深一点的绿：主操作按钮的底。 */
    const val GREEN_DK = 0xFF1F7A63.toInt()

    /** 琥珀：半授权（Shizuku 已授权但用户服务没连上）。 */
    const val AMBER = 0xFFE8B23A.toInt()

    /** 警示黄：提示行。 */
    const val WARN = 0xFFFFC857.toInt()

    /** 按下时叠上去的那层绿。 */
    const val PRESS = 0x2E2FBF71

    /** 中性按下（细线按钮）。 */
    const val PRESS_N = 0x33FFFFFF

    /** 透明。 */
    const val CLEAR = 0x00000000

    // ------------------------------------------------------------ 尺寸

    /** dp → px。 */
    fun dp(ctx: Context, v: Int): Int = Math.round(v * ctx.resources.displayMetrics.density)

    /** 等宽字体：数字（边距、坐标、轮次）和日志用它，几位数一眼对齐。 */
    fun mono(): Typeface = Typeface.MONOSPACE

    // ------------------------------------------------------------ 底

    /** 纯色块，直角（要圆角就传 radius）。 */
    fun block(fill: Int, stroke: Int = 0, strokeW: Int = 0, radius: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = radius.toFloat()
            setColor(fill)
            if (strokeW > 0 && stroke != 0) setStroke(strokeW, stroke)
        }

    /** 圆点（状态点）。 */
    fun circle(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    /**
     * 能点的东西的底：按下 → 选中 → 常态。
     *
     * 三态都画进同一个 StateListDrawable，视图只管设置 isSelected，
     * 不按的时候不用重新造 drawable，按下也一定有反馈。
     * [selFill] 为 0 表示这个控件没有选中态（普通按钮）。
     */
    fun pressable(
        fill: Int,
        pressed: Int,
        stroke: Int = 0,
        strokeW: Int = 0,
        selFill: Int = 0,
        selStroke: Int = 0,
        radius: Int = 0,
    ): StateListDrawable = StateListDrawable().apply {
        addState(
            intArrayOf(android.R.attr.state_pressed),
            block(pressed, if (selStroke != 0) selStroke else stroke, strokeW, radius),
        )
        if (selFill != 0) {
            addState(
                intArrayOf(android.R.attr.state_selected),
                block(selFill, if (selStroke != 0) selStroke else stroke, strokeW, radius),
            )
        }
        addState(intArrayOf(), block(fill, stroke, strokeW, radius))
    }

    // ------------------------------------------------------------ 控件

    /** 一句话的 TextView。[mono] 打开就是等宽。 */
    fun text(
        ctx: Context,
        s: String,
        sp: Float,
        color: Int = INK,
        mono: Boolean = false,
    ): TextView = TextView(ctx).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (mono) typeface = mono()
    }

    /** 小标题：10sp、次要色、带字距。 */
    fun label(ctx: Context, s: String): TextView = text(ctx, s, 10f, DIM2).apply {
        letterSpacing = 0.12f
        setPadding(0, dp(ctx, 3), 0, dp(ctx, 4))
    }

    /** 细线（分隔用）：按密度折成 1~2px，高密度屏上也看得见，又不会变成一条粗带。 */
    fun rule(ctx: Context, color: Int = LINE): View = View(ctx).apply {
        setBackgroundColor(color)
        val h = Math.max(1, Math.round(0.6f * ctx.resources.displayMetrics.density))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h)
    }

    /** 状态点：绿色 = 就绪，琥珀 = 半就绪，灰 = 没就绪。 */
    fun dot(ctx: Context, color: Int, size: Int = 8): View = View(ctx).apply {
        background = circle(color)
        layoutParams = LinearLayout.LayoutParams(dp(ctx, size), dp(ctx, size))
    }

    /** 把状态点换个颜色（轮询时用，不重建视图）。 */
    fun paint(v: View, color: Int) {
        (v.background as? GradientDrawable)?.setColor(color)
        v.invalidate()
    }

    /** 小标题行：左边标题、右边补充说明（可空）。 */
    fun section(ctx: Context, title: String, right: String? = null, rightColor: Int = DIM2): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 12), 0, dp(ctx, 4))
            addView(
                label(ctx, title).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                },
            )
            if (right != null) addView(text(ctx, right, 10f, rightColor, mono = true))
        }
}

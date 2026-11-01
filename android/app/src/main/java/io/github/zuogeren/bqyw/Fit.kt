package io.github.zuogeren.bqyw

import kotlin.math.roundToInt

/**
 * 基准坐标（2800×1260）→ 实际画面坐标。
 *
 * **游戏是按高度把 20:9 的画布铺满屏幕，再水平居中；屏幕比 20:9 窄时左右各裁掉一点。**
 * 这不是猜的：2376×1080（11:5）的实拍图上，15 个特征最小二乘解出 s=0.85677、ox=−11.4、
 * oy=0，残差 rms 0.29 px；而 1080/1260 = 0.85714、(2376−2400)/2 = −12。
 * 所以：
 *
 *   s  = h / 1260          （只按高度算，不看宽度）
 *   ox = (w − 2800·s) / 2  （比 20:9 窄时是负数 = 两侧被裁，宽时是正数）
 *   oy = 0                 （纵向永远贴边，实测 11 个特征残差 rms 0.30 px，没有黑边）
 *
 * 改之前用的是 min(w/2800, h/1260) + 上下留黑边，在 20:9 上两者完全等价，
 * 一换比例就整体偏：11:5 上老写法 s=0.8486（差 1%）、oy=5.4（凭空多出黑边），
 * 到屏幕右侧累积 11.6 px —— 轮次徽章窗口只有 38 px 宽，直接读错。
 * 详见 .notes/margin-android.md §11。
 */
class Fit(val w: Int, val h: Int) {

    /** 基准像素 → 实际像素的缩放：**只跟高度有关**。 */
    val s: Float = h / BASE_H

    /** 水平偏移：比 20:9 窄时是负数（两侧被裁掉一部分）。 */
    val ox: Float = (w - BASE_W * s) / 2f

    /** 纵向永远贴边，实测恒为 0。留这个字段是为了日志和对照，别再拿它算黑边。 */
    val oy: Float = 0f

    fun px(v: Float): Int = (ox + v * s).roundToInt()

    fun py(v: Float): Int = (v * s).roundToInt()

    companion object {
        /** 界面标定用的基准分辨率：2800×1260，20:9。 */
        const val BASE_W = 2800f
        const val BASE_H = 1260f
    }
}

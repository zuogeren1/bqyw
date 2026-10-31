package io.github.zuogeren.bqyw

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 运行日志：内存里留一份给界面看，同时写盘方便 adb 取。 */
object BotLog {

    /** 启动头第一行，和 PC 版的 applog.APP_NAME 一致。 */
    private const val APP_NAME = "百趣演武自动抽牌"

    private val lines = ArrayList<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val fmtFull = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private var file: java.io.File? = null

    @Synchronized
    fun attach(dir: java.io.File?) {
        file = if (dir == null) null else java.io.File(dir, "bot.log")
    }

    @Synchronized
    fun add(msg: String) {
        raw("[" + fmt.format(Date()) + "] " + msg)
    }

    /**
     * 启动头：和 PC 版日志开头那三行同一个写法（`#` 开头，不带时间戳）。
     *
     * [capW] / [capH] 是实际抓屏帧的尺寸（横屏 2800×1260 这种）；
     * 还没开始抓屏时按屏幕的横屏尺寸写，免得日志里出现 0×0。
     */
    @Suppress("DEPRECATION")
    @Synchronized
    fun header(ctx: Context, capW: Int = 0, capH: Int = 0) {
        // getRealMetrics：要的是物理屏幕，不是去掉状态栏之后的窗口尺寸
        val display = ctx.getSystemService(WindowManager::class.java).defaultDisplay
        val dm = DisplayMetrics().also { display.getRealMetrics(it) }
        // 屏幕按竖屏写（短边×长边）；窗口是实际抓屏帧，横屏时就是长边×短边
        val sw = minOf(dm.widthPixels, dm.heightPixels)
        val sh = maxOf(dm.widthPixels, dm.heightPixels)
        val ww = if (capW > 0) capW else sh
        val wh = if (capH > 0) capH else sw
        raw("# " + APP_NAME + " v" + BuildConfig.VERSION_NAME)
        raw(
            "# " + fmtFull.format(Date()) + "  Android " + Build.VERSION.RELEASE +
                "（SDK " + Build.VERSION.SDK_INT + "）  运行方式 apk"
        )
        raw(
            "# 屏幕 " + sw + "×" + sh + "（" + aspect(sw, sh) + "）  窗口 " +
                ww + "×" + wh + "（" + aspect(ww, wh) + "）"
        )
    }

    /** 宽高比：1260×2800 → 9:20，2560×1440 → 16:9。约不整也照实写，不四舍五入成 1.78。 */
    fun aspect(w: Int, h: Int): String {
        if (w <= 0 || h <= 0) return "?"
        var a = w
        var b = h
        while (b != 0) {
            val t = a % b
            a = b
            b = t
        }
        val g = if (a <= 0) 1 else a
        return (w / g).toString() + ":" + (h / g)
    }

    @Synchronized
    fun tail(n: Int): List<String> = lines.takeLast(n)

    @Synchronized
    fun clear() = lines.clear()

    /** 原样记一行：启动头那种不带时间戳的走这里。 */
    @Synchronized
    private fun raw(line: String) {
        if (lines.size > 400) lines.subList(0, 200).clear()
        lines.add(line)
        try {
            file?.appendText(line + "\n")
        } catch (_: Throwable) {
            // 写盘失败不影响运行
        }
    }
}

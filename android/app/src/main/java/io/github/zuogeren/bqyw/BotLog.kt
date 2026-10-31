package io.github.zuogeren.bqyw

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 运行日志：面板里留一份（跨运行累积），磁盘上每次开跑新写一份 run_*.log。
 *
 * 写盘规矩照抄 PC 的 bqyw/applog.py：一次运行一份、最多留 5 份、
 * 同一秒起两次不覆盖（加 _2 / _3）。
 */
object BotLog {

    /** 磁盘上最多留几份，和 PC 的 MAX_LOGS 一致。 */
    private const val MAX_LOGS = 5

    /** 启动头第一行，和 PC 版的 applog.APP_NAME 一致。 */
    private const val APP_NAME = "百趣演武自动抽牌"

    private val lines = ArrayList<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val fmtFull = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val fmtName = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    /** 日志目录：服务一起来就记下来。 */
    private var dir: File? = null

    /** 当前这次运行的那份文件；还没开跑就是 null。 */
    private var file: File? = null

    @Synchronized
    fun attach(d: File?) {
        dir = d
    }

    /**
     * 开跑：新建一份 run_yyyyMMdd_HHmmss.log，顺手把多出来的旧文件删掉。
     *
     * 和 PC 的 new_log 一个意思 —— 一次运行一份，头部的环境信息就是这一次的。
     * 面板里的历史不动；以前固定写的 bot.log 不再碰，也不删（用户数据）。
     */
    @Synchronized
    fun newRun(ctx: Context): File? {
        val d = (dir ?: ctx.getExternalFilesDir(null)) ?: return null
        dir = d
        d.mkdirs()
        val f = unique(File(d, "run_" + fmtName.format(Date()) + ".log"))
        val made = try {
            f.exists() || f.createNewFile()
        } catch (_: Throwable) {
            false
        }
        if (!made) {
            file = null
            return null
        }
        file = f
        prune(d)
        return f
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

    /** 当前这次运行的文件，导出 / 复制时用；还没开跑就是 null。 */
    @Synchronized
    fun runFile(): File? = file

    /** 面板 buffer 的全文：读不到文件时拿它兜底。 */
    @Synchronized
    fun panelText(): String = lines.joinToString("\n")

    @Synchronized
    fun tail(n: Int): List<String> = lines.takeLast(n)

    @Synchronized
    fun clear() = lines.clear()

    // ------------------------------------------------------------ 内部

    /** 只留最近 MAX_LOGS 份 run_*.log，从最旧的开始删；别的文件一概不碰。 */
    private fun prune(d: File) {
        val logs = (d.listFiles() ?: return)
            .filter { it.isFile && it.name.startsWith("run_") && it.name.endsWith(".log") }
            .sortedBy { it.lastModified() }
        var extra = logs.size - MAX_LOGS
        for (f in logs) {
            if (extra <= 0) break
            if (f.delete()) extra--
        }
    }

    /** 同一秒起两次也不互相覆盖：run_x.log 占着就写 run_x_2.log、run_x_3.log。 */
    private fun unique(path: File): File {
        if (!path.exists()) return path
        var n = 2
        while (true) {
            val cand = File(
                path.parentFile,
                path.nameWithoutExtension + "_" + n + "." + path.extension,
            )
            if (!cand.exists()) return cand
            n++
        }
    }

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

package io.github.zuogeren.bqyw

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 运行日志：内存里留一份给界面看，同时写盘方便 adb 取。 */
object BotLog {

    private val lines = ArrayList<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private var file: java.io.File? = null

    @Synchronized
    fun attach(dir: java.io.File?) {
        file = if (dir == null) null else java.io.File(dir, "bot.log")
    }

    @Synchronized
    fun add(msg: String) {
        if (lines.size > 400) lines.subList(0, 200).clear()
        val line = "[" + fmt.format(Date()) + "] " + msg
        lines.add(line)
        try {
            file?.appendText(line + "\n")
        } catch (_: Throwable) {
            // 写盘失败不影响运行
        }
    }

    @Synchronized
    fun tail(n: Int): List<String> = lines.takeLast(n)

    @Synchronized
    fun clear() = lines.clear()
}

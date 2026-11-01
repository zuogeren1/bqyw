package io.github.zuogeren.bqyw

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 主界面：三行授权（抓屏 / 点按方式 / 悬浮窗）→ 状态行 → 日志。
 *
 * 开始 / 停止这些运行控制只在悬浮球里，主界面只负责授权和看日志。
 * 全部是原生 View 手画的，颜色和间距都从 [Ui] 取，和悬浮球面板同一套视觉。
 * 授权行整行可点（按下有底色），状态点即时反映系统里的真实状态；
 * 日志用系统 ListView，几千行也不卡。
 */
class MainActivity : Activity() {

    // ------------------------------------------------------------ 视图引用

    /** 授权区的「n / 3 就绪」。 */
    private var authReady: TextView? = null

    /** 三行授权各自的状态点 + 状态文字。 */
    private val authDots = ArrayList<View>()
    private val authStates = ArrayList<TextView>()

    /** 「点按方式」那行的副标题：实时显示现在用的是无障碍还是 Shizuku。 */
    private var tapHint: TextView? = null

    /** 授权区下面那两行：运行状态、当前目标与边距。 */
    private var runLine: TextView? = null
    private var targetLine: TextView? = null

    /** 日志：面板里最多留这么多行，再多就把最旧的丢掉。 */
    private val logBuf = ArrayList<String>()

    /** 上一次从 [BotLog] 拿到的快照，用来算「新加了哪几行」。 */
    private var prevLog: List<String> = emptyList()

    private var logList: ListView? = null

    private val handler = Handler(Looper.getMainLooper())

    private val poll = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 500)
        }
    }

    // ------------------------------------------------------------ 生命周期

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CardReader.load(assets)
        Badge.load(assets)

        // targetSdk 35+ 强制边到边：内容会画到状态栏底下，自己把 inset 补回来
        val topInset = systemDimen("status_bar_height") + dp(10)
        val bottomInset = systemDimen("navigation_bar_height") + dp(8)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(dp(16), topInset, dp(16), bottomInset)
        }

        root.addView(header())
        root.addView(Ui.rule(this, Ui.LINE2))

        // 上半部分（授权 + 状态）按内容高度；小屏放不下时能翻
        val top = ScrollView(this).apply {
            isFillViewport = false
            addView(topContent())
        }
        root.addView(
            top,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        root.addView(logHeader())
        root.addView(
            logPanel(),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        setContentView(root)
        window.setBackgroundDrawable(ColorDrawable(Ui.BG))
        // 深色底：状态栏图标要用浅色（清掉原来那套「白底黑字」的设置）
        window.decorView.post { darkStatusBar() }
        refresh()
        handler.post(poll)
    }

    private fun header(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(4), 0, dp(8))
        addView(
            Ui.text(this@MainActivity, "百趣演武 · 自动抽牌", 15f, Ui.INK).apply {
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(
            Ui.text(this@MainActivity, "V" + BuildConfig.VERSION_NAME, 10.5f, Ui.DIM, mono = true),
        )
    }

    /** 上半部分：三行授权 + 状态行。 */
    private fun topContent(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL

        val head = Ui.section(this@MainActivity, "授权 · 3 项", "0 / 3 就绪", Ui.DIM2)
        addView(head)
        authReady = head.getChildAt(1) as TextView
        authRow(
            this, "①", "抓屏", "（点击授权）",
            "点了弹系统的录屏授权框，授权后才能读画面",
        ) { requestCapture() }
        // 无障碍和 Shizuku 都是「点按方式」，合成一行；点开再二选一
        tapHint = authRow(this, "②", "点按方式", "", tapHintText()) { showTapPicker() }
        authRow(
            this, "③", "悬浮窗", "（点击启用）",
            "允许「显示在其他应用上层」，悬浮球才出得来",
        ) { requestOverlay() }

        runLine = Ui.text(this@MainActivity, "", 11f, Ui.DIM, mono = true).apply {
            setPadding(0, dp(10), 0, 0)
        }
        addView(runLine)
        targetLine = Ui.text(this@MainActivity, "", 11f, Ui.DIM2).apply {
            setPadding(0, dp(3), 0, dp(8))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        addView(targetLine)
    }

    /**
     * 一行授权：状态点 + 名字（+ 动作后缀）+ 当前状态，第二行一句「点了会发生什么」。
     *
     * 整行可点，按下有绿色底。返回第二行那个 TextView，状态会变的那一行
     * （点按方式）拿它改写副标题。
     */
    private fun authRow(
        host: LinearLayout,
        no: String,
        name: String,
        suffix: String,
        hint: String,
        onClick: () -> Unit,
    ): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(9), dp(10), dp(9))
            isClickable = true
            background = Ui.pressable(Ui.CLEAR, Ui.PRESS)
            setOnClickListener { onClick() }
        }
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dot = Ui.dot(this, Ui.DIM, 7).apply {
            layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(8) }
        }
        authDots.add(dot)
        line.addView(dot)
        line.addView(
            Ui.text(this, no + " " + name, 14f, Ui.INK).apply {
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            },
        )
        if (suffix.isNotEmpty()) {
            line.addView(
                Ui.text(this, suffix, 11f, Ui.DIM).apply {
                    setPadding(dp(5), dp(1), 0, 0)
                },
            )
        }
        line.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val state = Ui.text(this, "—", 10.5f, Ui.DIM, mono = true)
        authStates.add(state)
        line.addView(state)
        row.addView(line)

        val sub = Ui.text(this, hint, 10.5f, Ui.DIM).apply {
            setPadding(dp(15), dp(3), 0, 0)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        row.addView(sub)
        host.addView(row)
        host.addView(Ui.rule(this))
        return sub
    }

    /** 日志标题行：左边标题，右边「导出 / 复制」两个细线小按钮。 */
    private fun logHeader(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(6), 0, dp(6))
        addView(
            Ui.label(this@MainActivity, "日志 · 自动滚到底").apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        addView(miniButton("导出") { exportLog() })
        addView(miniButton("复制") { copyLog() }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(30)).apply { marginStart = dp(6) }
        })
    }

    private fun miniButton(label: String, onClick: () -> Unit): TextView =
        Ui.text(this, label, 10f, Ui.DIM2).apply {
            gravity = Gravity.CENTER
            isClickable = true
            background = Ui.pressable(Ui.CLEAR, Ui.PRESS_N, Ui.LINE, dp(1))
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(30))
            setOnClickListener { onClick() }
        }

    /** 日志区：系统 ListView，几千行也只画看得见的那十几行。 */
    private fun logPanel(): View {
        val list = ListView(this).apply {
            divider = null
            dividerHeight = 0
            cacheColorHint = Ui.LOG_BG
            isVerticalScrollBarEnabled = true
            setPadding(dp(9), dp(6), dp(9), dp(6))
            clipToPadding = false
            adapter = logAdapter
        }
        logList = list
        return list.apply {
            background = Ui.block(Ui.LOG_BG, Ui.LINE, dp(1))
        }
    }

    private val logAdapter = object : BaseAdapter() {
        override fun getCount(): Int = logBuf.size

        override fun getItem(position: Int): Any = logBuf[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val tv = (convertView as? TextView) ?: TextView(this@MainActivity).apply {
                setTextColor(LOG_TEXT)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10.5f)
                typeface = Ui.mono()
                setPadding(0, dp(1), 0, dp(1))
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
            }
            tv.text = logBuf[position]
            return tv
        }
    }

    // ------------------------------------------------------------ 轮询刷新

    /**
     * 每 0.5 秒刷一次：授权状态、状态行、日志。
     *
     * 只改文字和状态点颜色，不重建视图 —— 主界面一直在前台，重建会闪。
     */
    private fun refresh() {
        val svc = CaptureService.instance
        val capture = svc?.hasProjection() == true
        val a11y = TapService.isReady()
        val shizuku = Auth.shizuku()
        val overlay = Settings.canDrawOverlays(this)
        val shizukuOn = shizuku == "已连接"

        setAuth(0, if (capture) "已授权" else "未授权", if (capture) 2 else 0)
        // 无障碍 / Shizuku 合成一行：右边写现在生效的那个，副标题再说明一次
        setAuth(
            1,
            if (a11y) "已开启" else shizuku,
            if (a11y || shizukuOn) 2 else if (shizuku == "已授权") 1 else 0,
        )
        setAuth(2, if (overlay) "已允许" else "未允许", if (overlay) 2 else 0)

        authReady?.text =
            listOf(capture, a11y || shizukuOn, overlay).count { it }.toString() + " / 3 就绪"
        tapHint?.text = tapHintText()

        runLine?.text = statusSpans(capture, a11y, shizukuOn, svc?.isBotRunning() == true)
        targetLine?.text = targetText()

        if (pullLog()) {
            logAdapter.notifyDataSetChanged()
            // 等这一轮布局算完再定位，否则会按旧行数去滚
            logList?.post { logList?.setSelection(logBuf.size - 1) }
        }
    }

    /** 授权行的状态点 + 文字。[level] 2 = 就绪（绿）、1 = 半就绪（琥珀）、0 = 没有（灰）。 */
    private fun setAuth(i: Int, text: String, level: Int) {
        val color = when (level) {
            2 -> Ui.GREEN
            1 -> Ui.AMBER
            else -> Ui.DIM
        }
        authDots.getOrNull(i)?.let { Ui.paint(it, color) }
        authStates.getOrNull(i)?.apply {
            this.text = text
            setTextColor(color)
        }
    }

    /** 「抓屏 就绪 · 点按 无障碍 · 机器人 空闲」，值跟着状态变色。 */
    private fun statusSpans(capture: Boolean, a11y: Boolean, shizuku: Boolean, running: Boolean): CharSequence {
        val sb = SpannableStringBuilder()
        fun part(label: String, value: String, color: Int) {
            if (sb.isNotEmpty()) sb.append("  ·  ")
            sb.append(label).append(" ")
            val from = sb.length
            sb.append(value)
            sb.setSpan(
                ForegroundColorSpan(color), from, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        part("抓屏", if (capture) "就绪" else "未授权", if (capture) Ui.GREEN else Ui.DIM)
        part("点按", when {
            a11y -> "无障碍"
            shizuku -> "Shizuku"
            else -> "无"
        }, if (a11y || shizuku) Ui.GREEN else Ui.DIM)
        part("机器人", if (running) "运行中" else "空闲", if (running) Ui.GREEN else Ui.DIM2)
        return sb
    }

    /** 目标行：当前目标 + 当前边距（开始按钮在悬浮球里，跑的就是这份）。 */
    private fun targetText(): String {
        val label = Selection.label()
        val margin = runCatching { MarginStore.load(this) }.getOrDefault(0)
        val target = if (label.isEmpty()) "目标 未选择（进悬浮球勾一个）" else "目标 " + label
        return target + "  ·  边距 " + margin
    }

    // ------------------------------------------------------------ 日志

    /**
     * 把 [BotLog] 里新写的行并进面板的缓冲。
     *
     * BotLog 自己只留最近 400 行，这里再多留一截（最多 [LOG_MAX] 行），
     * 一遍下来不用重复扫全文：拿上次的快照比一下，只追加新的那几行。
     * 返回 true 表示有新行，需要刷列表。
     */
    private fun pullLog(): Boolean {
        val fresh = BotLog.tail(LOG_SRC)
        if (fresh.isEmpty() || fresh == prevLog) return false
        val keep = overlap(prevLog, fresh)
        val add = fresh.subList(keep, fresh.size)
        prevLog = fresh
        if (add.isEmpty()) return false
        logBuf.addAll(add)
        if (logBuf.size > LOG_MAX) logBuf.subList(0, logBuf.size - LOG_MAX).clear()
        return true
    }

    /** 旧快照的后缀 == 新快照的前缀，最长能对上多少行。 */
    private fun overlap(old: List<String>, fresh: List<String>): Int {
        val max = Math.min(old.size, fresh.size)
        for (k in max downTo 1) {
            if (old[old.size - k] != fresh[0]) continue
            var same = true
            for (i in 0 until k) {
                if (old[old.size - k + i] != fresh[i]) {
                    same = false
                    break
                }
            }
            if (same) return k
        }
        return 0
    }

    // ------------------------------------------------------------ 授权动作

    private fun openAccessibility() {
        if (TapService.isReady()) {
            BotLog.add("授权：无障碍服务 " + Auth.a11y() + "，不用再开")
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            BotLog.add(
                "授权：无障碍服务 " + Auth.a11y() +
                    "，去设置列表里找到「" + getString(R.string.app_name) + "」打开它",
            )
        } catch (e: Throwable) {
            BotLog.add("[!] 打不开无障碍设置：" + e.javaClass.simpleName)
        }
    }

    private fun requestOverlay() {
        if (Settings.canDrawOverlays(this)) {
            BotLog.add("授权：悬浮窗 " + Auth.overlay(this) + "，不用再开")
            showOverlay()
            return
        }
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:" + packageName),
                ),
            )
            BotLog.add("授权：悬浮窗 未允许，去设置里打开「显示在其他应用上层」")
        } catch (e: Throwable) {
            BotLog.add("[!] 打不开悬浮窗设置：" + e.javaClass.simpleName)
        }
    }

    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            BotLog.add("授权：悬浮窗 " + Auth.overlay(this))
            return
        }
        val intent = Intent(this, CaptureService::class.java).apply {
            action = CaptureService.ACTION_SHOW_OVERLAY
        }
        startForegroundService(intent)
        BotLog.add("悬浮球已显示（拖动移动，点一下展开）")
    }

    private fun requestCapture() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    private fun requestShizuku() {
        if (!Shizuku.pingBinder()) {
            BotLog.add("授权：Shizuku " + Auth.shizuku())
            return
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            ShizukuLink.bind(packageName)
            BotLog.add("授权：Shizuku 已授权，正在连接用户服务")
        } else {
            BotLog.add("授权：Shizuku 未授权，正在申请")
            Shizuku.requestPermission(REQ_SHIZUKU)
        }
    }

    // ------------------------------------------------------------ 点按方式

    /**
     * 点按方式的副标题：现在实际在用哪一个。
     *
     * 两个都开着时按 [Tap.current] 的规矩走 —— 无障碍优先，这里要说清楚，
     * 免得有人以为「Shizuku 也开着，为什么日志里写的是无障碍」。
     */
    private fun tapHintText(): String {
        val a11y = Tap.a11yReady()
        val shell = Tap.shellReady()
        val authorized = Auth.shizuku() == "已授权"
        return when {
            a11y && shell -> "当前用 无障碍（两个都开着，无障碍优先）"
            a11y -> "当前用 无障碍"
            shell -> "当前用 Shizuku"
            // 授权过了但用户服务没连上：这时点按其实用不了，得说清楚是卡在哪一步
            authorized -> "Shizuku 已授权、没连上（点这里连）"
            else -> "都没开（点这里选）"
        }
    }

    /**
     * 点按方式二选一：无障碍 / Shizuku。
     *
     * 选完就走各自原来的流程（无障碍跳系统设置、Shizuku 走授权申请），
     * 没改任何判定 —— 起作用的还是 [Tap] 那一套（无障碍优先，失败自动换 Shizuku）。
     */
    private fun showTapPicker() {
        val a11y = Tap.a11yReady()
        val shell = Tap.shellReady()
        var dialog: AlertDialog? = null

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }
        box.addView(tapOption("无障碍", Auth.a11y(), a11y) {
            dialog?.dismiss()
            openAccessibility()
        })
        box.addView(tapOption("Shizuku", Auth.shizuku(), shell && !a11y) {
            dialog?.dismiss()
            requestShizuku()
        })

        dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("点按方式")
            .setView(box)
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
        // 系统弹窗默认是灰底，换成和面板同一套深色
        dialog.window?.setBackgroundDrawable(ColorDrawable(Ui.PANEL))
    }

    /** 弹窗里的一行：名字 + 当前状态，正在用的那个高亮 + 标「使用中」。 */
    private fun tapOption(name: String, state: String, active: Boolean, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(11), dp(10), dp(11))
            isClickable = true
            isSelected = active
            background = Ui.pressable(Ui.CLEAR, Ui.PRESS, selFill = 0x332FBF71)
            setOnClickListener { onClick() }
        }
        row.addView(
            Ui.dot(this, if (active) Ui.GREEN else Ui.DIM, 7).apply {
                layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(9) }
            },
        )
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(
            Ui.text(this, name, 14f, Ui.INK).apply {
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            },
        )
        col.addView(Ui.text(this, state, 10.5f, Ui.DIM))
        row.addView(
            col,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (active) row.addView(Ui.text(this, "使用中", 10.5f, Ui.GREEN))
        return row
    }

    // ------------------------------------------------------------ 日志导出 / 复制

    /** 导出日志：走 SAF（ACTION_CREATE_DOCUMENT），存哪儿由用户挑。 */
    private fun exportLog() {
        val name = "bqyw-log-" +
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".log"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, name)
        }
        try {
            startActivityForResult(intent, REQ_EXPORT)
        } catch (e: Throwable) {
            val msg = e.message ?: e.javaClass.simpleName
            toast("打不开导出窗口：" + msg)
            BotLog.add("[!] 打不开导出窗口：" + msg)
        }
    }

    /**
     * 写到用户选好的位置。
     *
     * 选完位置才读文件：这中间机器人可能又写了几行；读和写都在后台线程，别卡界面。
     */
    private fun exportTo(uri: android.net.Uri) {
        Thread {
            var saved = 0
            var why: String? = null
            try {
                val text = readLog()
                if (text.isEmpty()) {
                    why = "还没有日志"
                } else {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(text.toByteArray())
                    } ?: throw java.io.IOException("打不开目标文件")
                    saved = text.lineSequence().count { it.isNotEmpty() }
                }
            } catch (e: Throwable) {
                why = e.message ?: e.javaClass.simpleName
            }
            val where = displayName(uri)
            handler.post {
                val err = why
                if (err == null) {
                    toast("已导出 " + saved + " 行")
                    BotLog.add("已导出 " + saved + " 行到 " + where)
                } else {
                    toast("导出失败：" + err)
                    BotLog.add("[!] 导出失败：" + err)
                }
            }
        }.start()
    }

    /** 复制日志：同样的内容进剪贴板，读文件照样放后台线程。 */
    private fun copyLog() {
        Thread {
            val text = try {
                readLog()
            } catch (_: Throwable) {
                ""
            }
            handler.post {
                if (text.isEmpty()) {
                    toast("还没有日志")
                    return@post
                }
                try {
                    getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(android.content.ClipData.newPlainText("bqyw-log", text))
                } catch (e: Throwable) {
                    val msg = e.message ?: e.javaClass.simpleName
                    toast("复制失败：" + msg)
                    BotLog.add("[!] 复制失败：" + msg)
                    return@post
                }
                val n = text.lineSequence().count { it.isNotEmpty() }
                toast("已复制 " + n + " 行")
                BotLog.add("已复制 " + n + " 行（" + kb(text) + " KB）")
            }
        }.start()
    }

    /** 有这次运行的文件就读文件（全文），没开跑就用面板 buffer 兜底。 */
    private fun readLog(): String {
        val f = BotLog.runFile()
        if (f != null && f.exists()) {
            try {
                return f.readText()
            } catch (_: Throwable) {
                // 读不出来就退回面板，别让导出直接失败
            }
        }
        return BotLog.panelText()
    }

    /** SAF 只给了 uri，文件名得问系统要。 */
    private fun displayName(uri: android.net.Uri): String = try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        } ?: uri.lastPathSegment ?: "导出文件"
    } catch (_: Throwable) {
        uri.lastPathSegment ?: "导出文件"
    }

    /** 文本大小，KB、留一位小数。 */
    private fun kb(text: String): String =
        String.format(Locale.US, "%.1f", text.toByteArray().size / 1024.0)

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------ 系统回调

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_SHIZUKU) {
            val ok = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            BotLog.add("授权：Shizuku " + (if (ok) "已授权" else "未授权（被拒绝）"))
            if (ok) ShizukuLink.bind(packageName)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_EXPORT) {
            val uri = data?.data
            if (resultCode != RESULT_OK || uri == null) {
                BotLog.add("取消导出")
                return
            }
            exportTo(uri)
            return
        }
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            BotLog.add("授权：抓屏（MediaProjection） 已取消")
            return
        }
        val intent = Intent(this, CaptureService::class.java).apply {
            action = CaptureService.ACTION_START
            putExtra(CaptureService.EXTRA_CODE, resultCode)
            putExtra(CaptureService.EXTRA_DATA, data)
        }
        startForegroundService(intent)
        BotLog.add("授权：抓屏（MediaProjection） 已授权")
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置页回来时状态可能变了，别等下一轮轮询
        handler.post { refresh() }
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        super.onDestroy()
    }

    // ------------------------------------------------------------ 小工具

    private fun darkStatusBar() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                window.insetsController?.setSystemBarsAppearance(
                    0,
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                )
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = 0
            }
        } catch (_: Throwable) {
            // 设不上就保持默认，不影响使用
        }
    }

    private fun dp(v: Int): Int = Ui.dp(this, v)

    private fun systemDimen(name: String): Int {
        val id = resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    private companion object {
        const val REQ_CAPTURE = 1
        const val REQ_NOTIF = 2
        const val REQ_SHIZUKU = 3
        const val REQ_EXPORT = 4

        /** BotLog 面板里只留 400 行，从这里拿一手。 */
        const val LOG_SRC = 400

        /** 日志区最多留多少行（再多丢最旧的）。 */
        const val LOG_MAX = 3000

        /** 日志正文色。 */
        const val LOG_TEXT = 0xFF9FB0A8.toInt()
    }
}
package io.github.zuogeren.bqyw

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioManager
import android.media.Image
import android.media.ImageReader
import android.media.ToneGenerator
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import kotlin.math.roundToInt
import java.io.File
import java.util.Locale

/**
 * 主服务：前台服务 + MediaProjection 抓屏 + 跑抽牌状态机。
 *
 * 必须活在前台服务里：切到游戏后，普通 Activity 进程会被系统回收，
 * 连 8 秒后的延时点击都发不出去（这个坑实测踩过）。
 */
class CaptureService : Service() {

    companion object {
        const val CHANNEL = "capture"
        const val KEEP = 8
        const val ACTION_START = "start"
        const val ACTION_START_BOT = "start_bot"
        const val ACTION_STOP_BOT = "stop_bot"
        const val ACTION_SHOW_OVERLAY = "show_overlay"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_TARGET = "target"
        const val EXTRA_MAX = "max"
        const val EXTRA_HAND = "hand"
        const val EXTRA_ORDERED = "ordered"

        @Volatile
        var instance: CaptureService? = null
    }

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var worker: Thread? = null

    private var overlay: Overlay? = null

    /** 前台服务类型只许加不许减：把 mediaProjection 从已有会话里摘掉，系统会顺手停掉投屏。 */
    private var fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE

    private var botThread: Thread? = null
    @Volatile private var botStop = false
    @Volatile private var botRunning = false

    override fun onBind(intent: Intent?): IBinder? = null

    fun hasProjection(): Boolean = reader != null

    /**
     * 悬浮球点「开始」。
     *
     * [specs] 是每组自定义目标的牌面写法（一行一组），[handNames] 是勾上的标准牌型
     * （Hand.name）。两样可以同时有：达成其中任意一个就停手，优先级由 Targets.combine 排。
     */
    fun startBotFromUi(
        specs: List<String>,
        handNames: List<String>,
        ordered: Boolean = false,
    ) = startBot(specs, handNames, 0, ordered)

    fun stopBotFromUi() = stopBot()

    private fun ensureOverlay() {
        if (overlay?.isShowing == true) return
        if (!android.provider.Settings.canDrawOverlays(this)) return
        try {
            overlay = Overlay(this).also { it.show() }
        } catch (e: Throwable) {
            BotLog.add("[!] 悬浮球开不起来：" + e.javaClass.simpleName)
        }
    }

    fun isBotRunning(): Boolean = botRunning

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        BotLog.attach(getExternalFilesDir(null))
        // 只有真的要抓屏时才用 mediaProjection 类型：没有授权却声明它，
        // startForeground 会直接抛异常把进程带走（实测整个 App 被杀）
        val capturing = intent?.action != ACTION_START_BOT &&
            intent?.action != ACTION_STOP_BOT &&
            intent?.action != ACTION_SHOW_OVERLAY
        startInForeground(capturing)
        ensureOverlay()

        when (intent?.action) {
            ACTION_START_BOT -> {
                // 走 Intent 的老路子一次只能给一个目标（一项自定义或一个牌型）
                startBot(
                    listOfNotNull(
                        intent.getStringExtra(EXTRA_TARGET)?.takeIf { it.isNotBlank() }
                    ),
                    listOfNotNull(
                        intent.getStringExtra(EXTRA_HAND)?.takeIf { it.isNotBlank() }
                    ),
                    intent.getIntExtra(EXTRA_MAX, 0),
                    intent.getBooleanExtra(EXTRA_ORDERED, false),
                )
            }
            ACTION_STOP_BOT -> stopBot()
            ACTION_SHOW_OVERLAY -> ensureOverlay()
            else -> {
                val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
                val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent?.getParcelableExtra(EXTRA_DATA)
                }
                if (data != null && projection == null) startCapture(code, data)
            }
        }
        return START_NOT_STICKY
    }

    private fun startInForeground(capturing: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "抓屏", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val note: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("终末地抽牌助手运行中")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .build()
        if (capturing) {
            fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, note, fgsType)
        } else {
            startForeground(1, note)
        }
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        if (Build.VERSION.SDK_INT >= 29) startInForeground(true)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = mpm.getMediaProjection(resultCode, data) ?: return
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopCapture()
            }
        }, null)

        // 全分辨率：轮次徽章只有 19×40，半分辨率下认不稳（实测会乱跳）
        val dm = resources.displayMetrics
        val w = maxOf(dm.widthPixels, dm.heightPixels)
        val h = minOf(dm.widthPixels, dm.heightPixels)
        val imgReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = imgReader
        display = proj.createVirtualDisplay(
            "bqyw", w, h, dm.densityDpi / 2,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imgReader.surface, null, null
        )
        CardReader.load(assets)
        Badge.load(assets)
        startAutoSave()
    }

    // ------------------------------------------------------------ 机器人
    private fun startBot(
        specs: List<String>,
        handNames: List<String>,
        maxAttempts: Int,
        ordered: Boolean = false,
    ) {
        if (reader == null) {
            BotLog.add("[!] 还没授权抓屏，先在 App 里点「授权抓屏」")
            return
        }
        if (botRunning) {
            BotLog.add("[!] 已经在跑了")
            return
        }
        // 单个目标还是单个 Target，多选才包成 MultiTarget，组装规则和悬浮球的摘要一致
        val picked = try {
            Targets.combine(handNames, specs, ordered)
        } catch (e: Throwable) {
            BotLog.add("[!] 目标写法不对：" + (e.message ?: ""))
            return
        }
        // 旧数据里可能只勾了「散牌」：目标入口已经把它剔掉了（Targets.normalizeHands），
        // 剔完一个目标都不剩就退回四条（和 PC 版默认一致），
        // 不然这一轮会被当成「没选目标」直接不开局。
        val target = picked ?: if (handNames.isNotEmpty() || specs.isNotEmpty()) {
            BotLog.add("[!] 目标里的「散牌」不再支持，已改用「" + Hand.QUADS.cn + "」")
            Targets.combine(listOf(Targets.FALLBACK_HAND), emptyList(), ordered)
        } else {
            null
        }
        if (target == null) {
            BotLog.add("[!] 还没选目标：点悬浮球 → 牌型，勾一个再开始")
            return
        }

        // 敌人名单：关着就**完全不加载模板**（零开销）；名单非空才把 25 张立绘读进来
        var policy = EnemyStore.load(this)
        var book: Enemies.Book? = null
        if (policy.active()) {
            val b = Enemies.Book(assets)
            if (!b.ready) {
                BotLog.add("   [!] 敌人模板没装上（assets/enemies/enemies.json），名单不生效")
                policy = EnemyPolicy.OFF
            } else {
                book = b
                // 和 PC 版同一个写法：「敌人名单：黑名单：精锐行刑人」
                BotLog.add("敌人名单：" + policy)
                val miss = policy.names.filter { n -> n !in b.names() }
                if (miss.isNotEmpty()) {
                    BotLog.add("   [!] 这些名字模板库里没有，永远不会命中：" + miss.joinToString("、"))
                }
            }
        }

        val dm = resources.displayMetrics
        Geom.setScreen(
            maxOf(dm.widthPixels, dm.heightPixels),
            minOf(dm.widthPixels, dm.heightPixels),
        )
        // 边距补偿：悬浮球里填的值（存 SharedPreferences）。每次开跑读一次 ——
        // 面板里改完立刻生效，不用重开 App
        Geom.setMargin(MarginStore.load(this))
        // 画面尺寸写实际抓屏帧的，坐标缩放就是 Geom / Fit 正在用的那个
        val capW = reader?.width ?: 0
        val capH = reader?.height ?: 0

        botStop = false
        botRunning = true
        // 每次开跑新开一份 run_*.log（旧的多余的顺手删掉），头部写进这份文件
        BotLog.newRun(this)
        // 启动头 + 开跑三行，和 PC 版日志开头同一个写法
        BotLog.header(this, capW, capH)
        BotLog.add(
            "── 开始：" + target +
                "  重开上限=" + (if (maxAttempts > 0) maxAttempts.toString() else "不限"),
        )
        BotLog.add("目标：" + target)
        BotLog.add("抓取源：屏幕镜像")
        BotLog.add(
            "画面 " + capW + "×" + capH + "（" + BotLog.aspect(capW, capH) +
                "），坐标缩放 " + String.format(Locale.US, "%.3f", Fit(capW, capH).s),
        )
        // 边距为 0 时这两个坐标就是老版本的值，和以前的行一模一样
        BotLog.add(
            "边距 " + Geom.margin + "（基准内缩 " + num1(Geom.marginPx) + " px）：" +
                "抽牌 @(" + Geom.tapDraw().first + ", " + Geom.tapDraw().second + ")" +
                "  放弃 @(" + Geom.tapForfeit().first + ", " + Geom.tapForfeit().second + ")",
        )
        botThread = Thread {
            var reason = "aborted"
            var bot: Bot? = null
            try {
                // 无障碍是进程内的，可能已经就绪；Shizuku 是异步绑定，主线程不能等，所以放在这里
                ShizukuLink.bind(packageName)
                val end = System.currentTimeMillis() + 5000
                while (!Tap.ready() && System.currentTimeMillis() < end) Thread.sleep(100)
                Auth.log(this)
                if (!Tap.ready()) {
                    BotLog.add("   [!] " + tapWhy())
                    return@Thread
                }
                BotLog.add("点按方式：" + Tap.current())
                bot = Bot(engine, target, maxAttempts, policy, book) { BotLog.add(it) }
                reason = bot.run()
            } catch (e: Throwable) {
                BotLog.add("   [!] 异常：" + e.javaClass.simpleName + " " + (e.message ?: ""))
                reason = "error"
            } finally {
                botRunning = false
                botThread = null
                BotLog.add(
                    "── 结束（" + reasonCode(reason) + "）" +
                        (if (bot != null) "  " + bot.summary() else ""),
                )
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    /** 两种点按方式都不可用时，说清楚各自缺什么。 */
    private fun tapWhy(): String = when {
        !rikka.shizuku.Shizuku.pingBinder() ->
            "无障碍没开，Shizuku 服务也没在运行（打开其中任意一个即可）"
        rikka.shizuku.Shizuku.checkSelfPermission() !=
            android.content.pm.PackageManager.PERMISSION_GRANTED ->
            "无障碍没开，Shizuku 也还没授权给本应用（打开其中任意一个即可）"
        else -> "无障碍没开，Shizuku 用户服务连接超时（再点一次开始，或改用无障碍）"
    }

    private fun stopBot() {
        if (!botRunning) return
        botStop = true
        BotLog.add("停止：收到停止指令")
    }

    /**
     * 「试认」：把当前 5 张牌各自认成谁、几分写进日志。
     *
     * 真机调试就靠它：框对了分数应该 0.85 以上、最优偏移接近 0；
     * 最优偏移明显不为 0，就说明 Enemies 里那几个 ART_D* 常量要跟着挪（基准像素）。
     * 名单关着也能试认 —— 校准不需要先把名单打开。
     */
    fun probeEnemiesFromUi() {
        if (botRunning) {
            BotLog.add("[!] 先停下来再试认（机器人跑着的时候画面一直在变）")
            return
        }
        if (reader == null) {
            BotLog.add("[!] 还没授权抓屏，先在 App 里点「授权抓屏」")
            return
        }
        Thread {
            val policy = EnemyStore.load(this)
            val book = Enemies.Book(assets)
            if (!book.ready) {
                BotLog.add("[!] 敌人模板没装上（assets/enemies/enemies.json），名单不生效")
                return@Thread
            }
            val bmp = grabBitmap()
            if (bmp == null) {
                BotLog.add("[!] 试认：拿不到画面")
                return@Thread
            }
            try {
                // 服务刚起来时卡头模板可能还没载（load 是幂等的）
                CardReader.load(assets)
                Badge.load(assets)
                val fit = Fit(bmp.width, bmp.height)
                val cards = CardReader.readCards(bmp)
                BotLog.add(
                    "试认：名单 " + policy.label() + "，模板 " + book.size +
                        " 个，阈值 " + num(book.threshold) + "，坐标缩放 " + num3(fit.s),
                )
                // 框本身也打出来：真机上量一下实际像素就知道差多少
                val r0 = book.rectOf(bmp, 0)
                BotLog.add(
                    "     立绘框（基准 x=中心" + fmt(Enemies.ART_DX0) + "…" + fmt(Enemies.ART_DX1) +
                        "，y " + fmt(Enemies.ART_DY0) + "…" + fmt(Enemies.ART_DY1) +
                        "）第 1 张实际像素 " + r0.left + "," + r0.top + " → " +
                        r0.right + "," + r0.bottom,
                )
                var seen = 0
                for (i in 0 until 5) {
                    val point = cards.getOrNull(i)?.point ?: 0
                    if (point <= 0) {
                        BotLog.add("     第 " + (i + 1) + " 张：没翻开")
                        continue
                    }
                    seen++
                    val hit = book.best(bmp, i, point)
                    if (hit == null) {
                        BotLog.add("     第 " + (i + 1) + " 张（" + point + " 点）：认不出（裁不出立绘？）")
                        continue
                    }
                    val over = hit.score >= book.threshold
                    val sb = StringBuilder("     第 ").append(i + 1).append(" 张（")
                        .append(point).append(" 点）：")
                    if (over) {
                        sb.append(hit.name).append("（立绘 ").append(num(hit.score)).append("）")
                        if (policy.active()) {
                            sb.append(if (policy.shouldDrop(hit.name)) " → 弃" else " → 留")
                        }
                    } else {
                        sb.append("最像 ").append(hit.name).append("（").append(num(hit.score))
                            .append("），没到阈值")
                    }
                    BotLog.add(sb.toString())
                    val probe = book.bestOffset(bmp, i, point)
                    BotLog.add(
                        "        最优偏移 dx=" + fmt(probe.second) + " dy=" + fmt(probe.third) +
                            " 分数 " + num(probe.first) + "（默认框 " + num(hit.score) + "）",
                    )
                }
                if (seen == 0) BotLog.add("     牌桌是空的：先抽几张牌再试认")
            } catch (e: Throwable) {
                BotLog.add("[!] 试认失败：" + e.javaClass.simpleName + " " + (e.message ?: ""))
            } finally {
                bmp.recycle()
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    /** 偏移量写法：带符号整数（基准像素）。 */
    private fun fmt(v: Float): String = String.format(Locale.US, "%+.0f", v)

    /** 分数写法：两位小数。 */
    private fun num(v: Float): String = String.format(Locale.US, "%.2f", v)

    /** 缩放写法：三位小数。 */
    private fun num3(v: Float): String = String.format(Locale.US, "%.3f", v)

    /** 一位小数（边距折算出来的基准像素用）。 */
    private fun num1(v: Float): String = String.format(Locale.US, "%.1f", v)

    /** 不带符号的整数（基准坐标用）。 */
    private fun n0(v: Float): String = String.format(Locale.US, "%.0f", v)

    /**
     * 「校准」：把和边距有关的几个数一次写进日志，边距填错了靠这个一眼看出来。
     *
     * 三样：当前边距折算出的基准像素、补偿后的点击坐标（抽牌 / 放弃 / 弹窗确认）、
     * 轮次徽章读出来是 1 / 2 / 读不出；最后再用画面自己量一遍内缩量（抽牌按钮的
     * 青色左沿，边距 0 时固定在基准 x=2405，见 [Geom.DRAW_EDGE]）。
     *
     * 徽章读数是最灵的判据：窗口只有 19×40，m 差 10 px 以上窗口里就没字了
     * （实测只能偏 −11…+20 px），所以「徽章读得出 1」基本等于「边距填对了」。
     */
    fun probeMarginFromUi() {
        if (botRunning) {
            BotLog.add("[!] 先停下来再校准（机器人跑着的时候画面一直在变）")
            return
        }
        if (reader == null) {
            BotLog.add("[!] 还没授权抓屏，先在 App 里点「授权抓屏」")
            return
        }
        Thread {
            val bmp = grabBitmap()
            if (bmp == null) {
                BotLog.add("[!] 校准：拿不到画面")
                return@Thread
            }
            try {
                // 服务刚起来时模板可能还没载（load 是幂等的）
                Badge.load(assets)
                CardReader.load(assets)
                val fit = Fit(bmp.width, bmp.height)
                val m = Geom.marginPx
                val d = Geom.tapDraw()
                val f = Geom.tapForfeit()
                val c = Geom.tapConfirm()
                BotLog.add(
                    "校准：边距 " + Geom.margin + " → 基准内缩 " + num1(m) +
                        " px（k = " + Geom.MARGIN_K + "），画面 " +
                        bmp.width + "×" + bmp.height + "，缩放 " + num3(fit.s),
                )
                BotLog.add(
                    "     抽牌 " + n0(Geom.DRAW[0]) + " − " + n0(m) + " = " + n0(Geom.DRAW[0] - m) +
                        " → 屏幕 (" + d.first + ", " + d.second + ")" +
                        "   放弃 " + n0(Geom.FORFEIT[0]) + " − " + n0(m) + " = " +
                        n0(Geom.FORFEIT[0] - m) + " → 屏幕 (" + f.first + ", " + f.second + ")",
                )
                BotLog.add(
                    "     弹窗确认 " + n0(Geom.CONFIRM[0]) + "（居中，不动）→ 屏幕 (" +
                        c.first + ", " + c.second + ")",
                )
                val rnd = Badge.read(bmp)
                val win = Badge.window(bmp.width, bmp.height)
                val rndText = when (rnd) {
                    1 -> "1（第 1 轮）"
                    2 -> "2（不是第 1 轮，这时候「放弃挑战」＝退出整场演算）"
                    else -> "读不出"
                }
                val info = if (Badge.lastInfo.isEmpty()) "" else "（" + Badge.lastInfo + "）"
                BotLog.add(
                    "     轮次徽章：" + rndText + "   窗口 " +
                        win[0] + "," + win[1] + " → " + win[2] + "," + win[3] + info,
                )
                val real = measureMargin(bmp, fit)
                val realText = if (real == null) {
                    "量不出（抽牌按钮不是亮的青色：牌抽满了 / 不在抽牌页）"
                } else {
                    num1(real) + " px（按边距算是 " + num1(m) + "），抽牌按钮青色左沿 " +
                        n0(Geom.DRAW_EDGE - real) + "（边距 0 时是 " + n0(Geom.DRAW_EDGE) + "）"
                }
                BotLog.add("     实测内缩：" + realText)
            } catch (e: Throwable) {
                BotLog.add("[!] 校准失败：" + e.javaClass.simpleName + " " + (e.message ?: ""))
            } finally {
                bmp.recycle()
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 用画面里的死数当标尺量真实内缩了多少：抽牌按钮的青色左沿在边距 0 时固定在
     * 基准 x=2405（refs/安卓端-自定义边距-0.jpg 上量出来的，130 那张量到 2253，差 152）。
     *
     * 只在 y=885 这一行里找第一段够长的青色；找不到就返回 null（牌抽满了按钮是灰的、
     * 不在抽牌页、色值对不上…都算量不出）。**结果只写日志、不自动采用** ——
     * 色值毕竟是从两张参考图里挑的，宁可让人自己判断。
     */
    private fun measureMargin(bmp: Bitmap, fit: Fit): Float? {
        val y = fit.py(Geom.DRAW[1]).coerceIn(0, bmp.height - 1)
        // 边距 300 时左沿 = 2405 − 351 = 2054，留够两头
        val xa = fit.px(1900f).coerceIn(0, bmp.width - 1)
        val xb = fit.px(2600f).coerceIn(xa, bmp.width - 1)
        val n = xb - xa + 1
        if (n < 8) return null
        val row = IntArray(n)
        try {
            bmp.getPixels(row, 0, n, xa, y, n, 1)
        } catch (e: Throwable) {
            return null
        }
        // 够长的一段：基准 10 px 按缩放换成屏幕像素，太小的画面也别低于 4 px
        val minRun = (10f * fit.s).roundToInt().coerceAtLeast(4)
        var run = 0
        var i = 0
        while (i < n) {
            val c = row[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            // 按钮是亮的青蓝色（实测 RGB ≈ 101,193,216 / 118,230,254），背景是灰白
            if (b - r > 55 && g - r > 45) {
                run++
                if (run >= minRun) {
                    val base = (xa + (i - run + 1) - fit.ox) / fit.s
                    return Geom.DRAW_EDGE - base
                }
            } else {
                run = 0
            }
            i++
        }
        return null
    }

    /** 结束原因，和 PC 版同一个词表。 */
    private fun reasonCode(reason: String): String = when (reason) {
        "hit" -> "hit"
        "stopped" -> "stopped"
        "max" -> "max_attempts"
        "round2" -> "round2"
        "no_round" -> "round_unknown"
        // 开机自检发现实际布局和 Fit/边距算出来的对不上（比例不认识、边距填错）
        "no_layout" -> "layout_mismatch"
        "noframe" -> "bad_frame"
        "enemy_ban" -> "enemy_ban"
        "error" -> "error"
        // no_tap / stuck / stuck_draw：都是「没法继续」，归到 aborted
        else -> "aborted"
    }

    private val engine = object : Bot.Engine {
        override fun frame(): Bitmap? = grabBitmap()

        override fun tap(x: Int, y: Int): Boolean = Tap.tap(x, y)

        override fun beep() {
            try {
                val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, 400)
                Thread.sleep(500)
                tone.release()
            } catch (_: Throwable) {
                // 没声音不影响结果
            }
        }

        override fun isStopped(): Boolean = botStop || reader == null

        override fun saveDebug(bmp: Bitmap, name: String) {
            try {
                val f = File(getExternalFilesDir(null), name)
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
            } catch (_: Throwable) {
                // 调试用，失败无所谓
            }
        }
    }

    // ------------------------------------------------------------ 抓帧
    private fun startAutoSave() {
        if (worker != null) return
        worker = Thread {
            while (reader != null) {
                try {
                    if (!botRunning) tick()
                } catch (_: Throwable) {
                    // 抓失败就等下一轮
                }
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun tick() {
        val imgReader = reader ?: return
        val image: Image = try {
            imgReader.acquireLatestImage()
        } catch (e: IllegalStateException) {
            return
        } ?: return
        val bmp = try {
            toBitmap(image)
        } finally {
            image.close()
        }
        try {
            val f = File(getExternalFilesDir(null), "frame-" + System.currentTimeMillis() + ".png")
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            prune()
            val pts = CardReader.readPoints(bmp)
            File(getExternalFilesDir(null), "points.log").appendText(pts.joinToString(",") + "\n")
        } finally {
            bmp.recycle()
        }
    }

    private fun prune() {
        val files = getExternalFilesDir(null)?.listFiles()?.filter { it.name.endsWith(".png") }
            ?: return
        if (files.size <= KEEP) return
        files.sortedBy { it.lastModified() }.take(files.size - KEEP).forEach { it.delete() }
    }

    fun grabBitmap(): Bitmap? {
        val imgReader = reader ?: return null
        val image: Image = try {
            imgReader.acquireLatestImage()
        } catch (e: IllegalStateException) {
            null
        } ?: return null
        return try {
            toBitmap(image)
        } finally {
            image.close()
        }
    }

    private fun toBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val w = image.width
        val h = image.height
        val stride = plane.rowStride
        val padding = stride - plane.pixelStride * w
        val padded = Bitmap.createBitmap(w + padding / plane.pixelStride, h, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer)
        val out = Bitmap.createBitmap(padded, 0, 0, w, h)
        padded.recycle()
        return out
    }

    private fun stopCapture() {
        botStop = true
        worker?.interrupt()
        worker = null
        display?.release()
        display = null
        reader?.close()
        reader = null
        projection?.stop()
        projection = null
        stopSelf()
    }

    override fun onDestroy() {
        overlay?.hide()
        overlay = null
        instance = null
        stopCapture()
        super.onDestroy()
    }
}

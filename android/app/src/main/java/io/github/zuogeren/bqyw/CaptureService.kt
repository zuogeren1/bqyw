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
        const val EXTRA_FAST = "fast"

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
        fast: Boolean = false,
    ) = startBot(specs, handNames, 0, ordered, fast)

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
                    intent.getBooleanExtra(EXTRA_FAST, false),
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
        fast: Boolean = false,
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
        val target = try {
            Targets.combine(handNames, specs, ordered)
        } catch (e: Throwable) {
            BotLog.add("[!] 目标写法不对：" + (e.message ?: ""))
            return
        }
        if (target == null) {
            BotLog.add("[!] 还没选目标：点悬浮球 → 牌型，勾一个再开始")
            return
        }

        val dm = resources.displayMetrics
        Geom.setScreen(
            maxOf(dm.widthPixels, dm.heightPixels),
            minOf(dm.widthPixels, dm.heightPixels),
        )
        // 画面尺寸写实际抓屏帧的，坐标缩放就是 Geom / Fit 正在用的那个
        val capW = reader?.width ?: 0
        val capH = reader?.height ?: 0

        botStop = false
        botRunning = true
        // 启动头 + 开跑三行，和 PC 版日志开头同一个写法
        BotLog.header(this, capW, capH)
        BotLog.add(
            "── 开始：" + target +
                "  重开上限=" + (if (maxAttempts > 0) maxAttempts.toString() else "不限") +
                (if (fast) "  快速模式" else ""),
        )
        BotLog.add("目标：" + target)
        BotLog.add("抓取源：屏幕镜像" + (if (fast) "（快速模式：连抽 5 张）" else ""))
        BotLog.add(
            "画面 " + capW + "×" + capH + "（" + BotLog.aspect(capW, capH) +
                "），坐标缩放 " + String.format(Locale.US, "%.3f", Fit(capW, capH).s),
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
                bot = Bot(engine, target, maxAttempts, fast) { BotLog.add(it) }
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

    /** 结束原因，和 PC 版同一个词表。 */
    private fun reasonCode(reason: String): String = when (reason) {
        "hit" -> "hit"
        "stopped" -> "stopped"
        "max" -> "max_attempts"
        "round2" -> "round2"
        "no_round" -> "round_unknown"
        "noframe" -> "bad_frame"
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

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
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_TARGET = "target"
        const val EXTRA_MAX = "max"

        @Volatile
        var instance: CaptureService? = null
    }

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var worker: Thread? = null

    private var botThread: Thread? = null
    @Volatile private var botStop = false
    @Volatile private var botRunning = false

    override fun onBind(intent: Intent?): IBinder? = null

    fun hasProjection(): Boolean = reader != null

    fun isBotRunning(): Boolean = botRunning

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        BotLog.attach(getExternalFilesDir(null))
        startInForeground()

        when (intent?.action) {
            ACTION_START_BOT -> {
                startBot(
                    intent.getStringExtra(EXTRA_TARGET) ?: "",
                    intent.getIntExtra(EXTRA_MAX, 0),
                )
            }
            ACTION_STOP_BOT -> stopBot()
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

    private fun startInForeground() {
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
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, note)
        }
    }

    private fun startCapture(resultCode: Int, data: Intent) {
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
    private fun startBot(spec: String, maxAttempts: Int) {
        if (reader == null) {
            BotLog.add("还没授权抓屏，先在 App 里点「授权抓屏」")
            return
        }
        if (botRunning) {
            BotLog.add("已经在跑了")
            return
        }
        val target = try {
            Targets.parse(spec)
        } catch (e: Throwable) {
            BotLog.add("目标写法不对：" + (e.message ?: ""))
            return
        }

        val dm = resources.displayMetrics
        Geom.screenW = maxOf(dm.widthPixels, dm.heightPixels)
        Geom.screenH = minOf(dm.widthPixels, dm.heightPixels)

        botStop = false
        botRunning = true
        BotLog.add("开始：目标 " + target + (if (maxAttempts > 0) "，重开上限 " + maxAttempts else ""))
        botThread = Thread {
            var reason = "shizuku"
            try {
                // 绑定是异步的，主线程不能等，所以放在这里
                ShizukuLink.bind(packageName)
                val end = System.currentTimeMillis() + 5000
                while (!ShizukuLink.ready() && System.currentTimeMillis() < end) Thread.sleep(100)
                if (!ShizukuLink.ready()) {
                    BotLog.add("启动失败：" + shizukuWhy())
                    return@Thread
                }
                reason = Bot(engine, target, maxAttempts) { BotLog.add(it) }.run()
            } catch (e: Throwable) {
                BotLog.add("运行出错：" + e.javaClass.simpleName + " " + (e.message ?: ""))
            } finally {
                botRunning = false
                botThread = null
                BotLog.add("结束（" + reasonLabel(reason) + "）")
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun shizukuWhy(): String = when {
        !rikka.shizuku.Shizuku.pingBinder() -> "Shizuku 服务没在运行"
        rikka.shizuku.Shizuku.checkSelfPermission() !=
            android.content.pm.PackageManager.PERMISSION_GRANTED -> "还没给本应用授权 Shizuku"
        else -> "用户服务连接超时（Shizuku 可能刚重启，再点一次开始）"
    }

    private fun stopBot() {
        if (!botRunning) return
        botStop = true
        BotLog.add("收到停止指令")
    }

    private fun reasonLabel(reason: String): String = when (reason) {
        "hit" -> "命中"
        "max" -> "达到重开上限"
        "stopped" -> "已停止"
        "round2" -> "不是第 1 轮"
        "stuck" -> "牌桌一直是空的"
        "noframe" -> "没有画面"
        "no_round" -> "读不到轮次"
        "shizuku" -> "Shizuku 没连上"
        else -> reason
    }

    private val engine = object : Bot.Engine {
        override fun frame(): Bitmap? = grabBitmap()

        override fun tap(x: Int, y: Int): Boolean = ShizukuLink.tap(x, y)

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
        instance = null
        stopCapture()
        super.onDestroy()
    }
}

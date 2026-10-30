package io.github.zuogeren.bqyw

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import java.io.File

/**
 * 探针用的抓屏服务：真的走 MediaProjection，不是 screencap。
 * 只做三件事：拿到授权 → 建一个半分辨率的虚拟屏 → 按需把最新一帧存成 PNG。
 */
class CaptureService : Service() {

    companion object {
        const val CHANNEL = "capture"
        const val KEEP = 8
        const val ACTION_START = "start"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"

        /** Activity 通过它取帧；服务停了就置空。 */
        @Volatile
        var instance: CaptureService? = null
    }

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        startInForeground()

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        // API 33+ 用类型化的 getParcelableExtra
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }
        if (data != null && projection == null) {
            startCapture(code, data)
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
            .setContentTitle("抓屏探针运行中")
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
        // API 29 起必须先注册回调，否则建 VirtualDisplay 会直接抛 SecurityException
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopCapture()
            }
        }, null)

        // 游戏是横屏，虚拟屏按「长边 × 短边」建，否则竖屏建出来的画布会把画面挤成一条
        val dm = resources.displayMetrics
        val w = maxOf(dm.widthPixels, dm.heightPixels) / 2
        val h = minOf(dm.widthPixels, dm.heightPixels) / 2
        val imgReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = imgReader
        display = proj.createVirtualDisplay(
            "bqyw-probe", w, h, dm.densityDpi / 2,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imgReader.surface, null, null
        )
        startAutoSave()
    }

    /** 每 2 秒抓一帧存盘，只留最近 8 张：切到游戏后就不用再回来点按钮了。 */
    private fun startAutoSave() {
        if (worker != null) return
        worker = Thread {
            while (reader != null) {
                try {
                    grabToDisk()
                    prune()
                } catch (_: Throwable) {
                    // 探针：抓失败就等下一轮，不在这里报错
                }
                try {
                    Thread.sleep(2000)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun prune() {
        val files = getExternalFilesDir(null)?.listFiles()?.filter { it.name.endsWith(".png") }
            ?: return
        if (files.size <= KEEP) return
        files.sortedBy { it.lastModified() }.take(files.size - KEEP).forEach { it.delete() }
    }

    /** 最近存下来的那一张。 */
    fun latest(): File? =
        getExternalFilesDir(null)?.listFiles()
            ?.filter { it.name.endsWith(".png") }
            ?.maxByOrNull { it.lastModified() }

    /** 抓最新一帧存进 App 专属外部目录（adb pull 能直接拿走，不用权限）。 */
    fun grabToDisk(): File? {
        val imgReader = reader ?: return null
        val image: Image = try {
            imgReader.acquireLatestImage()
        } catch (e: IllegalStateException) {
            null
        } ?: return null
        return try {
            val bmp = toBitmap(image)
            val f = File(getExternalFilesDir(null), "frame-${System.currentTimeMillis()}.png")
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            f
        } finally {
            image.close()
        }
    }

    private fun toBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val w = image.width
        val h = image.height
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val padding = rowStride - pixelStride * w
        val padded = Bitmap.createBitmap(
            w + padding / pixelStride, h, Bitmap.Config.ARGB_8888
        )
        padded.copyPixelsFromBuffer(plane.buffer)
        val out = Bitmap.createBitmap(padded, 0, 0, w, h)
        padded.recycle()
        return out
    }

    private fun stopCapture() {
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

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }
}

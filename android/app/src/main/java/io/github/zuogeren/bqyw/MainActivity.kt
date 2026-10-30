package io.github.zuogeren.bqyw

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import rikka.shizuku.Shizuku
import java.io.File

/**
 * 探针界面：
 *  ①~③ MediaProjection 抓屏
 *  ④~⑤ Shizuku 注入点击
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var shizuku: TextView
    private var ctrl: IBinder? = null

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            ctrl = service
            runOnUiThread { shizuku.text = "Shizuku：用户服务已连接" }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            ctrl = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        status = TextView(this).apply {
            text = "抓屏：还没开始"
            textSize = 15f
            setPadding(0, 24, 0, 8)
        }
        shizuku = TextView(this).apply {
            text = "Shizuku：检查中…"
            textSize = 15f
            setPadding(0, 0, 0, 16)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 80, 60, 40)
            gravity = Gravity.TOP
        }

        fun button(label: String, onClick: () -> Unit) {
            root.addView(Button(this).apply {
                text = label
                setOnClickListener { onClick() }
            })
        }

        button("① 请求抓屏授权") {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            }
            val mpm = getSystemService(MediaProjectionManager::class.java)
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
        }

        button("② 看最新一帧") {
            // 落盘留痕：注入的点击到底有没有点到这个按钮，看 ui.log 就知道
            File(getExternalFilesDir(null), "ui.log").appendText("btn2 pressed\n")
            val file = CaptureService.instance?.latest()
            status.text = if (file == null) "抓屏：没拿到帧" else "抓屏：${file.absolutePath}"
        }

        button("③ 停止抓屏") {
            stopService(Intent(this, CaptureService::class.java))
            status.text = "抓屏：已停止"
        }

        button("④ 申请 Shizuku 权限") {
            if (!Shizuku.pingBinder()) {
                shizuku.text = "Shizuku：服务没在跑"
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                shizuku.text = "Shizuku：已经有权限，绑定用户服务"
                bindCtrl()
            } else {
                Shizuku.requestPermission(REQ_SHIZUKU)
            }
        }

        button("⑤ 15 秒后点抽牌（先切游戏）") { armTap(15000L, 2520, 885) }
        button("⑥ 8 秒后点一下屏幕正中") { armTap(8000L, 1400, 630) }
        // 自检：注入的点击如果落回本 App 的「②」按钮，状态栏会变，不依赖任何别的 App
        button("⑦ 5 秒后点②（自检）") { armTap(5000L, 630, 321) }
        // 探针：切到游戏后进程会被回收，注入就发不出去。真机上的正式版也必须是这个形态。
        button("⑨ 读点数（抓一帧识别）") {
            val bmp = CaptureService.instance?.grabBitmap()
            if (bmp == null) {
                status.text = "识别：没拿到帧（先做①授权抓屏）"
            } else {
                val pts = CardReader.readPoints(bmp)
                val size = "${bmp.width}x${bmp.height}"
                bmp.recycle()
                status.text = "识别 $size：" +
                    pts.joinToString(" ") { if (it == 0) "-" else it.toString() } +
                    "  合计 " + pts.sum()
            }
        }

        button("⑧ 保活（开前台服务，不抓屏）") {
            startForegroundService(Intent(this, CaptureService::class.java).apply {
                action = CaptureService.ACTION_START
            })
            status.text = "保活：前台服务已启动（App 切到后台也不会被杀）"
        }

        root.addView(status)
        root.addView(shizuku)
        setContentView(root)
        CardReader.load(assets)
        refreshShizuku()
    }

    private fun refreshShizuku() {
        shizuku.text = try {
            if (!Shizuku.pingBinder()) {
                "Shizuku：未连接（服务没跑 / 没装）"
            } else {
                val granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                "Shizuku：已连接 v${Shizuku.getVersion()}，授权=${if (granted) "有" else "无"}"
            }
        } catch (e: Throwable) {
            "Shizuku：检测失败 ${e.javaClass.simpleName}"
        }
    }

    private fun bindCtrl() {
        if (ctrl != null) return
        try {
            val args = Shizuku.UserServiceArgs(
                ComponentName(packageName, CtrlService::class.java.name)
            ).daemon(false).processNameSuffix("ctrl").debuggable(false).version(1)
            Shizuku.bindUserService(args, conn)
        } catch (e: Throwable) {
            shizuku.text = "Shizuku：绑定失败 ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun armTap(delayMs: Long, x: Int, y: Int) {
        val binder = ctrl
        if (binder == null) {
            status.text = "注入：还没连上 Shizuku 用户服务（先点④）"
            return
        }
        status.text = "注入：${delayMs / 1000} 秒后点 ($x, $y)…"
        Thread {
            try {
                Thread.sleep(delayMs)
            } catch (_: InterruptedException) {
                return@Thread
            }
            val note = try {
                val t0 = System.currentTimeMillis()
                tapVia(binder, x, y)
                "注入完成：${System.currentTimeMillis() - t0}ms"
            } catch (e: Throwable) {
                "注入失败：${e.javaClass.simpleName}: ${e.message}"
            }
            try {
                File(getExternalFilesDir(null), "tap.log").appendText("$note（$x,$y）\n")
            } catch (_: Throwable) {
                // 探针：写日志失败不影响结论
            }
            runOnUiThread { status.text = note }
        }.apply { isDaemon = true }.start()
    }

    /** 手写 Parcel 协议：写接口名 + 两个 int，让 shell 进程里的服务去 input tap。 */
    private fun tapVia(binder: IBinder, x: Int, y: Int) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(CtrlService.DESCRIPTOR)
            data.writeInt(x)
            data.writeInt(y)
            binder.transact(CtrlService.TX_TAP, data, reply, 0)
            reply.readException()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_SHIZUKU) return
        val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        shizuku.text = "Shizuku：${if (ok) "已允许" else "被拒绝"}"
        if (ok) bindCtrl()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            status.text = "抓屏：用户取消了授权"
            return
        }
        val intent = Intent(this, CaptureService::class.java).apply {
            action = CaptureService.ACTION_START
            putExtra(CaptureService.EXTRA_CODE, resultCode)
            putExtra(CaptureService.EXTRA_DATA, data)
        }
        startForegroundService(intent)
        status.text = "抓屏：已授权，虚拟屏启动中…"
    }

    override fun onDestroy() {
        stopService(Intent(this, CaptureService::class.java))
        super.onDestroy()
    }

    private companion object {
        const val REQ_CAPTURE = 1
        const val REQ_NOTIF = 2
        const val REQ_SHIZUKU = 3
    }
}

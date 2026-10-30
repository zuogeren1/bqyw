package io.github.zuogeren.bqyw

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import rikka.shizuku.Shizuku

/** 主界面：填目标 → 授权抓屏 → 开始；日志直接显示在下面。 */
class MainActivity : Activity() {

    private lateinit var targetInput: EditText
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private val handler = Handler(Looper.getMainLooper())

    private val poll = object : Runnable {
        override fun run() {
            val svc = CaptureService.instance
            val capture = if (svc?.hasProjection() == true) "就绪" else "未授权"
            val shizuku = when {
                !Shizuku.pingBinder() -> "未运行"
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> "未授权"
                ShizukuLink.ready() -> "已连接"
                else -> "等连接"
            }
            val bot = if (svc?.isBotRunning() == true) "运行中" else "空闲"
            status.text = "抓屏：" + capture + "   Shizuku：" + shizuku + "   机器人：" + bot
            logView.text = BotLog.tail(12).joinToString("\n")
            handler.postDelayed(this, 600)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CardReader.load(assets)
        Badge.load(assets)

        targetInput = EditText(this).apply {
            setText("5 5 5 5 任意")
            textSize = 16f
        }
        status = TextView(this).apply {
            textSize = 15f
            setPadding(0, 24, 0, 8)
        }
        logView = TextView(this).apply { textSize = 12f }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 60, 50, 30)
            gravity = Gravity.TOP
        }
        root.addView(TextView(this).apply {
            text = "目标点数（空格分隔，写「任意」表示不挑）"
            textSize = 14f
        })
        root.addView(targetInput)

        fun button(label: String, onClick: () -> Unit) {
            root.addView(Button(this).apply {
                text = label
                setOnClickListener { onClick() }
            })
        }

        button("① 授权抓屏") { requestCapture() }
        button("② 开始") { startBot() }
        button("③ 停止") { stopBot() }
        button("④ 授权 Shizuku") { requestShizuku() }

        root.addView(status)
        root.addView(logView)
        setContentView(root)
        handler.post(poll)
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
            BotLog.add("Shizuku 服务没在运行")
            return
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            ShizukuLink.bind(packageName)
            BotLog.add("Shizuku 已授权，正在连接用户服务")
        } else {
            BotLog.add("向 Shizuku 申请授权")
            Shizuku.requestPermission(REQ_SHIZUKU)
        }
    }

    private fun startBot() {
        val svc = CaptureService.instance
        if (svc?.hasProjection() != true) {
            BotLog.add("先点「① 授权抓屏」，并在弹窗里选「终末地」")
            return
        }
        if (svc.isBotRunning()) {
            BotLog.add("已经在跑了")
            return
        }
        if (!Shizuku.pingBinder()) {
            BotLog.add("Shizuku 服务没在运行")
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            requestShizuku()
            return
        }
        if (!ShizukuLink.ready()) ShizukuLink.bind(packageName)

        val intent = Intent(this, CaptureService::class.java).apply {
            action = CaptureService.ACTION_START_BOT
            putExtra(CaptureService.EXTRA_TARGET, targetInput.text.toString())
            putExtra(CaptureService.EXTRA_MAX, 0)
        }
        startForegroundService(intent)
        BotLog.add("下发开始指令，目标：" + targetInput.text.toString())
        // 自动切到游戏：机器人只在游戏前台时才点得对
        try {
            var launch = packageManager.getLaunchIntentForPackage(GAME_PKG)
            if (launch == null) {
                // 这个游戏没声明 LAUNCHER 入口，只能按组件名直接拉起来
                launch = Intent().setComponent(ComponentName(GAME_PKG, GAME_ACTIVITY))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(launch)
            BotLog.add("已切到游戏")
        } catch (e: Throwable) {
            BotLog.add("切游戏失败：" + e.javaClass.simpleName)
        }
    }

    private fun stopBot() {
        val intent = Intent(this, CaptureService::class.java).apply {
            action = CaptureService.ACTION_STOP_BOT
        }
        startForegroundService(intent)
        BotLog.add("下发停止指令")
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_SHIZUKU) {
            val ok = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            BotLog.add(if (ok) "Shizuku 授权成功" else "Shizuku 授权被拒绝")
            if (ok) ShizukuLink.bind(packageName)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            BotLog.add("取消了抓屏授权")
            return
        }
        val intent = Intent(this, CaptureService::class.java).apply {
            action = CaptureService.ACTION_START
            putExtra(CaptureService.EXTRA_CODE, resultCode)
            putExtra(CaptureService.EXTRA_DATA, data)
        }
        startForegroundService(intent)
        BotLog.add("抓屏授权成功")
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        super.onDestroy()
    }

    private companion object {
        const val REQ_CAPTURE = 1
        const val REQ_NOTIF = 2
        const val REQ_SHIZUKU = 3
        const val GAME_PKG = "com.hypergryph.endfield"
        const val GAME_ACTIVITY = "com.u8.sdk.U8UnityContext"
    }
}

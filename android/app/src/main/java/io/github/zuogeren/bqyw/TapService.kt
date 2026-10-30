package io.github.zuogeren.bqyw

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 无障碍点击服务：用 dispatchGesture 在屏幕坐标上画一个极短的手势。
 *
 * 这是第二种点按方式（第一种是 Shizuku 的 shell `input tap`）。
 * 好处是不用另外装东西，在系统设置里打开一次就一直有效，重启也不会掉；
 * 代价是系统会常驻一条「无障碍已开启」的提示。
 *
 * 只申请 canPerformGestures，不读窗口内容。
 */
class TapService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        attach(this)
        BotLog.add("无障碍点按已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 只要手势能力，界面事件一概不看
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        detach()
        BotLog.add("无障碍点按已断开")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        detach()
        super.onDestroy()
    }

    /**
     * 在 (x, y) 点一下。
     *
     * 手势是异步派发的，这里等系统回调（比固定 sleep 快，也不会抢在抬手前就返回）；
     * 返回 false 就说明这一下没打出去，调用方可以换回 shell 补救。
     */
    fun tapAt(x: Int, y: Int): Boolean {
        if (!isReady()) return false
        // 只用 moveTo 的路径在部分机型上会被当成空手势，补 1px 位移最稳
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
            lineTo((x + 1).toFloat(), y.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_MS))
            .build()
        val done = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        val sent = try {
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    ok.set(true)
                    done.countDown()
                }

                override fun onCancelled(g: GestureDescription?) {
                    done.countDown()
                }
            }, null)
        } catch (e: Throwable) {
            false
        }
        if (!sent) return false
        done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return ok.get()
    }

    companion object {
        /** 按下到抬起：太短会被当成丢帧，40ms 和 input tap 的手感接近。 */
        private const val TAP_MS = 40L

        /** 等手势回调的上限：卡住也不能把整轮流程拖死。 */
        private const val TIMEOUT_MS = 700L

        @Volatile
        var instance: TapService? = null
            private set

        fun isReady(): Boolean = instance != null

        private fun attach(svc: TapService) {
            instance = svc
        }

        private fun detach() {
            instance = null
        }
    }
}

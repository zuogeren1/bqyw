package io.github.zuogeren.bqyw

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import rikka.shizuku.Shizuku

/** 和 shell 进程里的用户服务握手。拿到 binder 就能发点击。 */
object ShizukuLink {

    private var binder: IBinder? = null

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
        }
    }

    fun ready(): Boolean = binder != null

    fun bind(pkg: String): Boolean {
        if (binder != null) return true
        if (!Shizuku.pingBinder()) return false
        return try {
            val args = Shizuku.UserServiceArgs(ComponentName(pkg, CtrlService::class.java.name))
                .daemon(false)
                .processNameSuffix("ctrl")
                .debuggable(false)
                .version(1)
            Shizuku.bindUserService(args, conn)
            true
        } catch (e: Throwable) {
            false
        }
    }

    fun tap(x: Int, y: Int): Boolean {
        val b = binder ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(CtrlService.DESCRIPTOR)
            data.writeInt(x)
            data.writeInt(y)
            b.transact(CtrlService.TX_TAP, data, reply, 0)
            reply.readException()
            true
        } catch (e: Throwable) {
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}

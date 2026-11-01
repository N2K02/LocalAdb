package dev.n2k02.localadb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Parcel
import rikka.shizuku.Shizuku

/**
 * Supports legacy/implicit Shizuku Binder requests. The server still enforces
 * each application's authorization; this only delivers its restricted Binder.
 */
class ShizukuBinderRequestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "rikka.shizuku.intent.action.REQUEST_BINDER") return
        val binder = runCatching { intent.getBundleExtra("data")?.getBinder("binder") }.getOrNull()
        val shizukuBinder = runCatching { Shizuku.getBinder() }.getOrNull()
        if (binder == null || shizukuBinder == null || !shizukuBinder.pingBinder()) {
            if (AdbEngine.status.value.connected) NativeShizukuManager.start(context)
            return
        }
        val data = Parcel.obtain()
        try {
            data.writeStrongBinder(shizukuBinder)
            data.writeString(context.applicationInfo.sourceDir)
            binder.transact(1, data, null, IBinder.FLAG_ONEWAY)
        } catch (_: Exception) {
            // A malformed requester must never crash the Shizuku manager.
        } finally { data.recycle() }
    }
}

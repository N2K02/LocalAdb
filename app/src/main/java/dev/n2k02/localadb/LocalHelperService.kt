package dev.n2k02.localadb

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process

/** Instantiated by Shizuku in a separate shell process, never an Android Service. */
class LocalHelperService : Binder() {
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == DESTROY) {
            // Shizuku's documented destroy transaction; only kills this test process.
            kotlin.system.exitProcess(0)
        }
        if (code == PING) {
            data.enforceInterface(DESCRIPTOR)
            val nonce = data.readString()
            checkNotNull(reply).apply {
                writeNoException()
                writeInt(Process.myUid())
                writeString(nonce)
            }
            return true
        }
        return super.onTransact(code, data, reply, flags)
    }

    companion object {
        const val DESCRIPTOR = "dev.n2k02.localadb.LocalHelperService"
        const val PING = IBinder.FIRST_CALL_TRANSACTION
        const val DESTROY = 16777115
    }
}

package dev.n2k02.localadb

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Grants only eligible LocalAdb-owned permissions after live UID 2000 verification. */
internal object AdbPermissionBootstrap {
    data class State(
        val finished: Boolean = false,
        val granted: Set<String> = emptySet(),
        val unavailable: Set<String> = emptySet(),
    )
    val state = MutableStateFlow(State())
    private val mutex = Mutex()

    fun eligible(sdk: Int, restoreAfterBoot: Boolean): List<String> = buildList {
        if (sdk >= 33) add("android.permission.NEARBY_WIFI_DEVICES")
        if (sdk >= 37) add("android.permission.ACCESS_LOCAL_NETWORK")
        if (restoreAfterBoot) add("android.permission.WRITE_SECURE_SETTINGS")
    }

    suspend fun configure(context: Context) = mutex.withLock {
        val app = context.applicationContext
        val result = mutableSetOf<String>()
        val refused = mutableSetOf<String>()
        val uid = AdbTransport.exec("id -u", 3_000).getOrNull()?.trim()
        if (uid != "2000" || !AdbEngine.status.value.connected) {
            state.value = State(unavailable = setOf("Verified shell UID 2000 required"))
            return@withLock
        }
        val requested = eligible(Build.VERSION.SDK_INT,
            AdbSettings.get(app, AdbSettings.Option.RESTORE_AFTER_RESTART))
        for (permission in requested) {
            if (app.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                try {
                    val safePackage = app.packageName
                    AdbTransport.exec("pm grant $safePackage $permission", 10_000)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // The Android readback below decides the result, never shell stdout.
                }
            }
            if (app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
                result += permission
            } else refused += permission
        }
        state.value = State(finished = true, granted = result, unavailable = refused)
    }

    fun reset() { state.value = State() }
}

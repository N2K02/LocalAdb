package dev.n2k02.localadb

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** VRec-like automatic app bootstrap, only after checking live shell UID 2000. */
internal object AdbPermissionBootstrap {
    data class State(
        val busy: Boolean = false,
        val finished: Boolean = false,
        val granted: List<String> = emptyList(),
        val unavailable: List<String> = emptyList(),
    )
    val status = MutableStateFlow(State())
    private val mutex = Mutex()
    internal fun eligiblePermissions(sdk: Int, restoreEnabled: Boolean): List<String> = buildList {
        if (sdk >= 33) {
            add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        if (sdk >= 37) add("android.permission.ACCESS_LOCAL_NETWORK")
        if (sdk >= 30 && restoreEnabled) add(Manifest.permission.WRITE_SECURE_SETTINGS)
    }
    suspend fun configure(context: Context): State = mutex.withLock {
        try {
            configureLocked(context.applicationContext)
        } catch (e: CancellationException) {
            status.value = State(unavailable = listOf("Interrupted"))
            throw e
        } catch (e: Exception) {
            State(finished = true, unavailable = listOf(e.message ?: "Permission setup failed"))
                .also { status.value = it }
        }
    }
    private suspend fun configureLocked(context: Context): State {
        if (!AdbEngine.status.value.connected || !AdbTransport.isConnected ||
            AdbTransport.exec("id -u", timeoutMs = 3_000).getOrNull()?.trim() != "2000") {
            return State(unavailable = listOf("No live shell UID 2000"))
                .also { status.value = it }
        }
        status.value = State(busy = true)
        val granted = mutableListOf<String>()
        val unavailable = mutableListOf<String>()
        for (permission in eligiblePermissions(
            Build.VERSION.SDK_INT, AdbSettings.get(context, AdbSettings.Option.RESTORE_AFTER_RESTART)
        )) {
            val label = permission.substringAfterLast('.')
            if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                AdbTransport.exec("pm grant " + context.packageName + " " + permission, timeoutMs = 10_000)
            }
            if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED)
                granted.add(label)
            else unavailable.add(label + " (Android denied; check App info)")
        }
        return State(finished = true, granted = granted, unavailable = unavailable)
            .also { status.value = it }
    }
    fun reset() { status.value = State() }
}

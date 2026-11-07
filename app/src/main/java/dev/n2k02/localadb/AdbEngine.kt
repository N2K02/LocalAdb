package dev.n2k02.localadb

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import dev.n2k02.adb.core.AdbCore
import dev.n2k02.adb.core.AdbOption
import dev.n2k02.adb.core.AdbPhase
import dev.n2k02.adb.core.AdbState
import dev.n2k02.adb.core.AdbTransportMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Compatibility status for LocalAdb dashboards, permission bootstrap and Shizuku. */
internal data class AdbStatus(
    val phase: Phase = Phase.IDLE,
    val message: String = "Ready to set up Android Wireless debugging.",
    val scanning: Boolean = false,
    val pairingPort: Int? = null,
    val tlsPort: Int? = null,
    val shellUid: Int? = null,
    val tcpConnected: Boolean = false,
) {
    val connected: Boolean get() = phase == Phase.CONNECTED && shellUid == 2000
}
internal enum class Phase { IDLE, SEARCHING, PAIRING, CONNECTING, CONNECTED, NEEDS_HELP, STOPPED }

/** Only a presentation/lifecycle facade. All wire, discovery, locking, retry,
 * watchdog and UID verification belong to n2k02AdbCoreLibrary. */
internal object AdbEngine {
    val status = MutableStateFlow(AdbStatus())
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observer: Job? = null
    private var helperRecovery: Job? = null
    @Volatile private var active = false
    private var ownedCore: AdbCore? = null

    private fun statusOf(s: AdbState): AdbStatus = AdbStatus(
        phase = when (s.phase) {
            AdbPhase.VERIFIED -> Phase.CONNECTED
            AdbPhase.SEARCHING -> Phase.SEARCHING
            AdbPhase.PAIRING -> Phase.PAIRING
            AdbPhase.CONNECTING -> Phase.CONNECTING
            AdbPhase.NEEDS_SETUP -> Phase.NEEDS_HELP
            AdbPhase.STOPPED -> Phase.STOPPED
            AdbPhase.IDLE -> Phase.IDLE
        },
        message = s.message,
        scanning = s.scanning,
        pairingPort = s.pairingPort,
        tlsPort = s.connectionPort,
        shellUid = if (s.connected) 2000 else null,
        tcpConnected = s.connected && s.transport == AdbTransportMode.LOCAL_TCP_5555,
    )

    /** Host hooks run concurrently with StateFlow observers. Publish the
     * verified state before Shizuku/permission checks inspect the dashboard. */
    internal fun publishVerified(adb: AdbCore) {
        status.value = statusOf(adb.state.value)
    }

    @Synchronized fun start(context: Context, explicit: Boolean = false) {
        val app = context.applicationContext
        if (explicit) AdbSettings.setStopped(app, false)
        if (AdbSettings.stopped(app)) return
        val shared = SharedAdbHostAdapter.core(app)
        if (!active || ownedCore !== shared) {
            if (scope.coroutineContext[Job]?.isActive != true) {
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            }
            observer?.cancel()
            active = true
            ownedCore = shared
            status.value = statusOf(shared.state.value)
            observer = scope.launch {
                shared.state.collect { status.value = statusOf(it) }
            }
            shared.acquireService()
        }
        AdbRecoveryWorker.schedule(app)
        updateHelperRecovery(app, AdbSettings.get(app, AdbSettings.Option.WATCHDOG))
        if (explicit) retry(app)
    }

    /** Shizuku is app-specific, not a second ADB watchdog. ADB transport and
     * UID checks remain exclusively in AdbCore; this only checks Binder health.
     * Keep it alive for the service, independently from Settings visibility. */
    @Synchronized internal fun updateHelperRecovery(context: Context, enabled: Boolean) {
        helperRecovery?.cancel()
        helperRecovery = null
        val app = context.applicationContext
        if (!enabled || !active || AdbSettings.stopped(app)) return
        val shared = ownedCore ?: return
        helperRecovery = scope.launch {
            while (isActive && !AdbSettings.stopped(app)) {
                delay(120_000L)
                if (shared.state.value.connected) NativeShizukuManager.maybeRecover(app)
            }
        }
    }

    fun retry(context: Context) {
        if (!active || AdbSettings.stopped(context)) return
        val shared = ownedCore ?: return
        scope.launch { shared.retry(auto = false) }
    }

    /** Called only for an Android Settings code, never persisted. */
    fun submitPairingCode(context: Context, code: String) {
        val port = status.value.pairingPort ?: return
        if (!code.matches(Regex("[0-9]{6}"))) return
        val shared = ownedCore ?: return
        if (AdbSettings.stopped(context)) return
        scope.launch {
            if (shared.pair(port, code)) {
                status.value.tlsPort?.let { shared.connectTls(it) }
            }
        }
    }

    fun preferencesChanged(context: Context) {
        val app = context.applicationContext
        AdbRecoveryWorker.schedule(app)
        val shared = ownedCore ?: return
        scope.launch {
            for (option in AdbOption.entries) {
                shared.setOption(option,
                    AdbSettings.get(app, AdbSettings.Option.valueOf(option.name)))
            }
        }
    }

    fun disableTcp(context: Context) {
        val app = context.applicationContext
        AdbSettings.set(app, AdbSettings.Option.TCP_5555, false)
        val shared = ownedCore ?: return
        scope.launch { shared.disableTcp() }
    }

    fun openDeveloperOptions(context: Context) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Preserve explicit opt-in and same-boot off safeguards from LocalAdb. */
    fun restoreAfterReboot(context: Context): RestorePolicy.Decision {
        val app = context.applicationContext
        val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val wifi = connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val decision = AdbSettings.canRestore(app, wifi)
        if (decision == RestorePolicy.Decision.RESTORE) {
            val success = runCatching {
                Settings.Global.putInt(app.contentResolver, "adb_wifi_enabled", 1)
            }.getOrDefault(false)
            if (success) return RestorePolicy.Decision.RESTORE
        }
        return decision
    }

    /** Quit is awaited off the UI thread and cannot leave a second ADB socket. */
    suspend fun shutdown(context: Context) {
        val app = context.applicationContext
        AdbSettings.setStopped(app, true)
        active = false
        observer?.cancel()
        observer = null
        helperRecovery?.cancel()
        helperRecovery = null
        scope.cancel()
        AdbRecoveryWorker.cancel(app)
        withContext(NonCancellable + Dispatchers.IO) {
            SharedAdbHostAdapter.shutdown()
        }
        ownedCore = null
        status.value = AdbStatus(phase = Phase.STOPPED, message = "Stopped by Quit.")
    }
}

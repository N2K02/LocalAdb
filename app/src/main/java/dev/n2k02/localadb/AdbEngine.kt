package dev.n2k02.localadb

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.InetAddress
import java.net.NetworkInterface

internal data class AdbStatus(
    val phase: Phase = Phase.IDLE,
    val message: String = "Ready to set up Android Wireless debugging.",
    val scanning: Boolean = false,
    val pairingPort: Int? = null,
    val tlsPort: Int? = null,
    val shellUid: Int? = null,
    val tcpConnected: Boolean = false
) {
    val connected: Boolean get() = shellUid == 2000 && phase == Phase.CONNECTED
}
internal enum class Phase { IDLE, SEARCHING, PAIRING, CONNECTING, CONNECTED, NEEDS_HELP, STOPPED }

/** Local-device ADB, never a network remote-shell client. */
internal object AdbEngine {
    val status = MutableStateFlow(AdbStatus())
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectionMutex = Mutex()
    private var watchdog: Job? = null
    private var active = false
    private var nsd: NsdManager? = null
    private var wifiLock: WifiManager.MulticastLock? = null
    private var pairingDiscovery: NsdManager.DiscoveryListener? = null
    private var tlsDiscovery: NsdManager.DiscoveryListener? = null
    private var generation = 0
    private var pairingAttempt = false

    fun start(context: Context, explicit: Boolean = false) {
        val app = context.applicationContext
        if (explicit) AdbSettings.setStopped(app, false)
        if (AdbSettings.stopped(app)) return
        if (active) {
            if (explicit) retry(app)
            return
        }
        active = true
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        AdbRecoveryWorker.schedule(app)
        startWatchdog(app)
        retry(app)
    }

    fun retry(context: Context) {
        val app = context.applicationContext
        if (!active || AdbSettings.stopped(app)) return
        scope.launch {
            connectionMutex.withLock {
                if (!active) return@withLock
                // Avoid a needless disconnect when a verified session is
                // already in the requested ADB mode.
                val tcpWanted = AdbSettings.get(app, AdbSettings.Option.TCP_5555)
                if (status.value.connected && AdbTransport.lastKnownConnected &&
                    (!tcpWanted || status.value.tcpConnected) && verifyShell()) {
                    NativeShizukuManager.maybeRecover(app)
                    return@withLock
                }
                status.update { it.copy(phase = Phase.CONNECTING, message = "Checking authorized localhost ADB…") }
                if (connectTcp(app)) return@withLock
                val port = AdbSettings.tlsPort(app)
                if (port != null && connectTls(app, port)) return@withLock
                startDiscovery(app)
            }
        }
    }

    private suspend fun verifyShell(): Boolean {
        val raw = AdbTransport.exec("id -u").getOrNull() ?: return false
        return raw.trim().lines().lastOrNull()?.trim() == "2000"
    }

    private suspend fun connectTcp(context: Context): Boolean {
        if (status.value.connected && status.value.tcpConnected &&
            AdbTransport.lastKnownConnected && verifyShell()) return true
        // The port can be open without being an authorized Android shell.
        if (!isLocalPortListening()) return false
        AdbTransport.disconnect()
        val result = AdbTransport.connect(context, AdbTransport.LOOPBACK, 5555)
        if (result.isFailure || !verifyShell()) {
            AdbTransport.disconnect()
            return false
        }
        onVerified(context, tcp = true)
        return true
    }

    private suspend fun connectTls(context: Context, port: Int): Boolean {
        if (port !in 1..65535 || !active) return false
        if (status.value.connected && AdbTransport.lastKnownConnected &&
            verifyShell()) return true
        AdbTransport.disconnect()
        val connected = AdbTransport.connectLocalTls(context, port)
        if (connected.isFailure || !verifyShell()) {
            AdbTransport.disconnect()
            return false
        }
        AdbSettings.rememberVerified(context, tlsPort = port)
        if (AdbSettings.get(context, AdbSettings.Option.TCP_5555)) {
            status.update { it.copy(phase = Phase.CONNECTING, message = "Switching authorized shell to TCP/5555…") }
            val switched = AdbTransport.switchToTcpip()
            if (switched.isSuccess) {
                repeat(4) {
                    delay(1_100L)
                    if (connectTcp(context)) return true
                }
            }
            status.update { it.copy(phase = Phase.NEEDS_HELP,
                message = "TLS was verified, but TCP/5555 reconnect failed. Retry, or turn off TCP/5555.") }
            return false
        }
        onVerified(context, tcp = false)
        return true
    }

    private suspend fun onVerified(context: Context, tcp: Boolean) {
        AdbSettings.rememberVerified(context)
        status.update { it.copy(phase = Phase.CONNECTED, shellUid = 2000, tcpConnected = tcp,
            message = if (tcp) "Authorized Android shell (UID 2000), localhost TCP/5555."
                      else "Authorized Android shell (UID 2000), Wireless debugging TLS.") }
        stopDiscovery()
        // Native Shizuku autostart must not wait for permission bootstrap:
        // a denied optional Android permission must not suppress server startup.
        NativeShizukuManager.start(context)
        scope.launch { AdbPermissionBootstrap.configure(context) }
        if (AdbSettings.get(context, AdbSettings.Option.RESTORE_AFTER_RESTART)) {
            // This is opt-in, and is attempted only after verifying our own shell.
            val safePackage = context.packageName.takeIf { it.matches(Regex("[A-Za-z0-9_.]+")) } ?: return
            if (context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                AdbTransport.exec("pm grant $safePackage android.permission.WRITE_SECURE_SETTINGS")
            }
        }
    }

    fun submitPairingCode(context: Context, code: String) {
        if (!code.matches(Regex("[0-9]{6}")) || !active || pairingAttempt) return
        val port = status.value.pairingPort ?: run {
            status.update { it.copy(message = "Open 'Pair device with pairing code' in Android Wireless debugging and tap Find.") }
            return
        }
        pairingAttempt = true
        val app = context.applicationContext
        scope.launch {
            try {
                connectionMutex.withLock {
                    if (!active) return@withLock
                    status.update { it.copy(phase = Phase.PAIRING, message = "Authorizing this phone's ADB client…") }
                    val paired = AdbTransport.pairLocal(app, port, code)
                    if (paired.isSuccess) {
                        AdbSettings.markPaired(app)
                        status.update { it.copy(phase = Phase.SEARCHING, message = "Paired. Discovering Android ADB connection…") }
                        status.value.tlsPort?.let { connectTls(app, it) }
                    } else {
                        status.update { it.copy(phase = Phase.NEEDS_HELP, message = "Pairing failed. Verify the six-digit code and retry.") }
                    }
                }
            } finally {
                pairingAttempt = false
            }
        }
    }

    private fun isLocalPortListening(): Boolean = try {
        java.net.Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress("127.0.0.1", 5555), 350)
            true
        }
    } catch (_: Exception) { false }

    private fun onDevice(address: InetAddress): Boolean {
        if (address.isLoopbackAddress) return true
        return runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching false
            while (interfaces.hasMoreElements()) {
                val network = interfaces.nextElement()
                if (!network.isUp) continue
                val addresses = network.inetAddresses
                while (addresses.hasMoreElements()) if (addresses.nextElement() == address) return@runCatching true
            }
            false
        }.getOrDefault(false)
    }

    private fun startDiscovery(context: Context) {
        if (!active || Build.VERSION.SDK_INT < 30) {
            status.update { it.copy(phase = Phase.NEEDS_HELP, message = "Wireless debugging requires Android 11+. Use USB ADB where supported.") }
            return
        }
        stopDiscovery()
        val mgr = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: run {
            status.update { it.copy(phase = Phase.NEEDS_HELP, message = "Android network discovery unavailable.") }
            return
        }
        nsd = mgr
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifiLock = runCatching {
            wifi?.createMulticastLock("localadb-mdns")?.apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
        val current = ++generation
        status.update { it.copy(phase = Phase.SEARCHING, scanning = true,
            pairingPort = null, tlsPort = null, shellUid = null,
            message = "Finding Android Wireless debugging on this device…") }
        pairingDiscovery = listen(mgr, "_adb-tls-pairing._tcp.", current) { port ->
            status.update { it.copy(pairingPort = port,
                message = "Pairing service found. Enter the six-digit code, or enable auto-detect in Accessibility.") }
        }
        tlsDiscovery = listen(mgr, "_adb-tls-connect._tcp.", current) { port ->
            status.update { it.copy(tlsPort = port) }
            scope.launch {
                connectionMutex.withLock {
                    if (active && current == generation && !pairingAttempt) connectTls(context, port)
                }
            }
        }
        // A bounded scan avoids holding a Wi-Fi multicast lock indefinitely.
        scope.launch {
            delay(180_000L)
            if (current == generation && !status.value.connected) {
                stopDiscovery()
                status.update { it.copy(phase = Phase.NEEDS_HELP,
                    message = "No authorized ADB connection found. Check Wireless debugging, pairing and Wi-Fi.") }
            }
        }
    }

    private fun listen(
        mgr: NsdManager, type: String, current: Int, onPort: (Int) -> Unit
    ): NsdManager.DiscoveryListener {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                status.update { it.copy(message = "Wireless discovery unavailable ($errorCode); retry from settings.") }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!active || current != generation) return
                @Suppress("DEPRECATION")
                mgr.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        if (!active || current != generation) return
                        @Suppress("DEPRECATION")
                        val host = serviceInfo.host
                        if (host != null && onDevice(host) && serviceInfo.port in 1..65535) {
                            onPort(serviceInfo.port)
                        }
                    }
                })
            }
        }
        runCatching { mgr.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { status.update { s -> s.copy(message = "Could not start wireless discovery.") } }
        return listener
    }

    private fun stopDiscovery() {
        ++generation
        val mgr = nsd
        if (mgr != null) {
            listOfNotNull(pairingDiscovery, tlsDiscovery).forEach { listener ->
                runCatching { mgr.stopServiceDiscovery(listener) }
            }
        }
        pairingDiscovery = null
        tlsDiscovery = null
        nsd = null
        runCatching { wifiLock?.release() }
        wifiLock = null
        status.update { it.copy(scanning = false) }
    }

    private fun startWatchdog(context: Context) {
        watchdog?.cancel()
        watchdog = scope.launch {
            while (isActive && active && !AdbSettings.stopped(context)) {
                delay(45_000L)
                if (!AdbSettings.get(context, AdbSettings.Option.WATCHDOG)) continue
                if (status.value.connected && AdbTransport.isConnected && verifyShell()) {
                    // A verified ADB connection can outlive the embedded Shizuku Binder.
                    // Recovery is backoff-limited and respects a deliberate Stop.
                    NativeShizukuManager.maybeRecover(context)
                    continue
                }
                status.update { it.copy(phase = Phase.IDLE, shellUid = null, tcpConnected = false,
                    message = "ADB connection lost. Attempting authorized recovery…") }
                retry(context)
            }
        }
    }

    fun preferencesChanged(context: Context) {
        val app = context.applicationContext
        AdbRecoveryWorker.schedule(app)
        if (!AdbSettings.get(app, AdbSettings.Option.WATCHDOG)) {
            watchdog?.cancel()
            watchdog = null
        } else if (active && watchdog == null) {
            startWatchdog(app)
        }
    }

    fun disableTcp(context: Context) {
        val app = context.applicationContext
        AdbSettings.set(app, AdbSettings.Option.TCP_5555, false)
        scope.launch {
            connectionMutex.withLock {
                if (status.value.tcpConnected && status.value.connected) {
                    val switched = AdbTransport.switchToUsb()
                    status.update { it.copy(phase = Phase.IDLE, shellUid = null, tcpConnected = false,
                        message = if (switched.isSuccess) "TCP/5555 disabled. Reconnect through Wireless debugging."
                        else "Could not disable TCP/5555. Confirm the setting on Android and retry.") }
                    if (switched.isSuccess) startDiscovery(app)
                }
            }
        }
    }

    fun openDeveloperOptions(context: Context) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Startup on boot never revokes a user-off choice within the same boot. */
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

    fun shutdown(context: Context) {
        AdbSettings.setStopped(context, true)
        active = false
        watchdog?.cancel()
        watchdog = null
        stopDiscovery()
        scope.cancel()
        // Quit must close the socket and shell before the Activity is removed.
        runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(2_000L) { AdbTransport.close() }
        }
        AdbRecoveryWorker.cancel(context)
        status.value = AdbStatus(phase = Phase.STOPPED, message = "Stopped by Quit.")
    }
}

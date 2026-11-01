package dev.n2k02.localadb

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/** Runs the Apache-2.0 Shizuku-Next server inside LocalAdb, not in a separate app. */
// The Shizuku manager-only API methods are restricted to the library group
// for ordinary clients. Runtime verification restricts calls to LocalAdb
// when it owns the MANAGER permission and the Binder is live.
@SuppressLint("RestrictedApi")
internal object NativeShizukuManager {
    const val MANAGER_PERMISSION = "moe.shizuku.manager.permission.MANAGER"
    const val SHIZUKU_API_PERMISSION = "moe.shizuku.manager.permission.API_V23"
    const val FLAG_ALLOWED = 1 shl 1
    const val FLAG_DENIED = 1 shl 2
    const val MASK_PERMISSION = FLAG_ALLOWED or FLAG_DENIED
    const val SERVER_SHA256 = "f75b8b74727d281f3d7fd242298f676bfb4ee81d2feb5298aa719b05284fa149"

    internal data class Status(
        val starting: Boolean = false,
        val serviceReady: Boolean = false,
        val shellUid: Int? = null,
        val message: String = "Shizuku server not started.",
    )
    val state = MutableStateFlow(Status())

    private const val PREF = "native_shizuku"
    private const val ENABLED = "autostart"
    private val mutex = Mutex()
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var launch: Job? = null

    fun autostart(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(ENABLED, true)

    fun setAutostart(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean(ENABLED, enabled).apply()
    }

    fun managerPermissionOwner(context: Context): String? = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPermissionInfo(MANAGER_PERMISSION, 0).packageName
    }.getOrNull()

    internal fun ownerMatches(owner: String?, packageName: String): Boolean =
        owner == packageName

    private fun ensureValidOwnership(context: Context) {
        check(ownerMatches(managerPermissionOwner(context), context.packageName)) {
            "Shizuku manager permission belongs to another installed app. " +
                "Disable or uninstall that manager before LocalAdb can act as the manager."
        }
        check(context.packageManager.checkPermission(MANAGER_PERMISSION, context.packageName) ==
            PackageManager.PERMISSION_GRANTED) {
            "Android has not granted LocalAdb the Shizuku manager permission"
        }
    }

    /** Binder liveness is not the same as an installed app or a successful shell command. */
    fun refresh(context: Context) {
        val ready = runCatching {
            ensureValidOwnership(context)
            Shizuku.pingBinder() && Shizuku.getUid() == Process.SHELL_UID &&
                Shizuku.getVersion() >= 13
        }.getOrDefault(false)
        state.update {
            it.copy(serviceReady = ready, shellUid = if (ready) Process.SHELL_UID else null,
                message = if (ready) "Bundled Shizuku server connected · UID 2000"
                          else if (it.starting) it.message
                          else "Bundled Shizuku server unavailable; connect local ADB to start.")
        }
    }

    fun start(context: Context, explicit: Boolean = false) {
        val app = context.applicationContext
        if (!explicit && !autostart(app)) return
        if (launch?.isActive == true) return
        if (scope.coroutineContext[Job]?.isActive != true) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }
        launch = scope.launch {
            mutex.withLock {
                try {
                    ensureValidOwnership(app)
                    refresh(app)
                    if (state.value.serviceReady) return@withLock
                    state.value = Status(starting = true,
                        message = "Verifying ADB shell and extracting bundled server…")
                    // A cached status badge is not sufficient; revalidate the live ADB shell.
                    check(AdbEngine.status.value.connected && AdbTransport.isConnected &&
                        AdbTransport.exec("id -u", 3_000).getOrNull()?.trim() == "2000") {
                        "Connect authorized local ADB shell UID 2000 before starting Shizuku"
                    }
                    val packageName = app.packageName
                    check(packageName.matches(Regex("[A-Za-z0-9_.]+"))) {
                        "Invalid package name"
                    }
                    val dir = "/data/local/tmp/" + packageName + "-shizuku"
                    val file = "$dir/base.apk"
                    val uri = "content://$packageName.embedded-shizuku/embedded"
                    // The expected digest is checked on the device BEFORE using app_process.
                    val extraction = "mkdir -p $dir && chmod 700 $dir && " +
                        "content read --uri $uri > $file && chmod 600 $file && " +
                        "sha256sum $file"
                    val digestResult = AdbTransport.exec(extraction, 120_000).getOrThrow()
                    check(digestResult.lines().any {
                        it.trim().startsWith(SERVER_SHA256 + " ")
                    }) { "Shizuku server payload checksum mismatch or shell extraction failed" }

                    state.value = Status(starting = true,
                        message = "Starting Shizuku-Next server under shell UID 2000…")
                    // Launch from a directory whose basename starts with our package ID,
                    // which the Apache-2.0 server identifies as its manager package.
                    val command = "CLASSPATH=$file app_process /system/bin " +
                        "rikka.shizuku.server.ShizukuService " +
                        "> $dir/server.log 2>&1 < /dev/null &"
                    AdbTransport.exec(command, 10_000).getOrThrow()
                    val started = SystemClock.elapsedRealtime()
                    while (SystemClock.elapsedRealtime() - started < 25_000) {
                        if (!scope.coroutineContext[Job].let { it?.isActive == true }) break
                        if (Shizuku.pingBinder()) {
                            refresh(app)
                            if (state.value.serviceReady) return@withLock
                        }
                        delay(550)
                    }
                    error("Shizuku server did not deliver a working manager Binder")
                } catch (cancelled: CancellationException) {
                    state.value = Status(message = "Shizuku startup cancelled")
                    throw cancelled
                } catch (failure: Exception) {
                    state.value = Status(message = failure.localizedMessage
                        ?: "Shizuku server startup failed")
                } finally {
                    state.update { it.copy(starting = false) }
                }
            }
        }
    }

    /** Null means not verified, never interpret it as a denied Shizuku grant. */
    fun granted(context: Context, uid: Int): Boolean? {
        if (!state.value.serviceReady || uid < 0 ||
            uid / 100_000 != Process.myUid() / 100_000 ||
            !ownerMatches(managerPermissionOwner(context), context.packageName)) return null
        return runCatching {
            val result = Shizuku.getFlagsForUid(uid, MASK_PERMISSION)
            (result and FLAG_ALLOWED) == FLAG_ALLOWED
        }.getOrNull()
    }

    suspend fun changeGrant(context: Context, uid: Int, allow: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                require(uid >= 0 && uid / 100_000 == Process.myUid() / 100_000)
                ensureValidOwnership(context)
                check(state.value.serviceReady && Shizuku.pingBinder()) {
                    "Native Shizuku manager is not connected"
                }
                val requestedClient = context.packageManager.getPackagesForUid(uid).orEmpty().any { name ->
                    runCatching {
                        @Suppress("DEPRECATION")
                        val packageInfo = context.packageManager.getPackageInfo(
                            name, PackageManager.GET_PERMISSIONS)
                        packageInfo.requestedPermissions?.contains(SHIZUKU_API_PERMISSION) == true
                    }.getOrDefault(false)
                }
                require(requestedClient) { "App does not declare the Shizuku API permission" }
                Shizuku.updateFlagsForUid(uid, MASK_PERMISSION,
                    if (allow) FLAG_ALLOWED else FLAG_DENIED)
                val verified = granted(context, uid)
                check(verified == allow) { "Shizuku did not confirm this permission change" }
                Result.success(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Result.failure(failure)
            }
        }

    fun shutdown(context: Context) {
        launch?.cancel()
        launch = null
        scope.cancel()
        // A manager process may have survived this app's activity restart.
        if (ownerMatches(managerPermissionOwner(context), context.packageName) &&
            Shizuku.pingBinder()) runCatching { Shizuku.exit() }
        state.value = Status(message = "Stopped by Quit.")
    }
}

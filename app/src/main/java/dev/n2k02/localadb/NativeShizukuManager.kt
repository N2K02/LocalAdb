package dev.n2k02.localadb

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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
        val stopping: Boolean = false,
        val serviceReady: Boolean = false,
        val manuallyStopped: Boolean = false,
        val diagnostics: String = "",
        val shellUid: Int? = null,
        val message: String = "Shizuku server not started.",
    )
    val state = MutableStateFlow(Status())

    private const val PREF = "native_shizuku"
    private const val ENABLED = "autostart"
    private const val MANUAL_STOP = "manual_stop"
    private const val TAG = "LocalAdbShizuku"
    private const val PROCESS_NAME = "localadb_shizuku_server"
    private val mutex = Mutex()
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var launch: Job? = null
    private var lastAttemptMs = 0L

    fun autostart(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(ENABLED, true)

    fun setAutostart(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean(ENABLED, enabled).apply()
    }

    fun manuallyStopped(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(MANUAL_STOP, false)

    private fun setManuallyStopped(context: Context, stopped: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean(MANUAL_STOP, stopped).apply()
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

    /** A live manager binder, *not* an APK install or an optimistic shell result. */
    private fun binderReady(context: Context): Boolean = runCatching {
        ensureValidOwnership(context)
        Shizuku.pingBinder() && Shizuku.getUid() == Process.SHELL_UID &&
            Shizuku.getVersion() >= 13
    }.getOrDefault(false)

    /** Never overwrite the last failure with a generic "unavailable" message. */
    fun refresh(context: Context) {
        val ready = binderReady(context)
        val stopped = manuallyStopped(context)
        state.update { old ->
            old.copy(
                serviceReady = ready,
                shellUid = if (ready) Process.SHELL_UID else null,
                manuallyStopped = stopped,
                message = when {
                    ready -> "Bundled Shizuku server connected · UID 2000"
                    old.starting || old.stopping -> old.message
                    stopped -> "Stopped manually. Tap Restart to run again."
                    old.diagnostics.isNotBlank() -> old.message
                    old.serviceReady -> "Shizuku Binder disconnected; recovery is available."
                    else -> old.message
                }
            )
        }
    }

    private fun ensureScope() {
        if (scope.coroutineContext[Job]?.isActive != true) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }
    }

    /**
     * ADB UID 2000 verification triggers this automatically. A deliberate Stop
     * persists through activity recreation, watchdog checks and device restart.
     */
    fun start(context: Context, explicit: Boolean = false) {
        val app = context.applicationContext
        if (!explicit && (!autostart(app) || manuallyStopped(app))) {
            if (manuallyStopped(app)) state.update {
                it.copy(manuallyStopped = true,
                    message = "Stopped manually. Tap Restart to run again.")
            }
            return
        }
        if (!AdbEngine.status.value.connected || !AdbTransport.isConnected) return
        if (launch?.isActive == true) return
        if (state.value.serviceReady && binderReady(app)) return
        ensureScope()
        launch = scope.launch { mutex.withLock { startLocked(app) } }
    }

    /** ADB watchdog checks both transport and the Shizuku binder. */
    fun maybeRecover(context: Context) {
        val app = context.applicationContext
        if (state.value.serviceReady && binderReady(app)) return
        refresh(app)
        val sinceLastAttempt = if (lastAttemptMs == 0L) Long.MAX_VALUE
            else (SystemClock.elapsedRealtime() - lastAttemptMs).coerceAtLeast(0)
        if (!ShizukuRecoveryPolicy.shouldRecover(
                adbVerified = AdbEngine.status.value.connected && AdbTransport.isConnected,
                autoEnabled = autostart(app),
                manuallyStopped = manuallyStopped(app),
                busy = launch?.isActive == true,
                binderReady = state.value.serviceReady,
                elapsedMs = sinceLastAttempt
            )) return
        Log.i(TAG, "Shizuku binder unhealthy; automatic retry after backoff")
        start(app)
    }

    /** Explicit Stop disables auto-restart until the user asks to Restart. */
    fun stop(context: Context) {
        val app = context.applicationContext
        setManuallyStopped(app, true)
        launch?.cancel()
        ensureScope()
        launch = scope.launch { mutex.withLock { stopLocked(app) } }
    }

    /** Explicit Restart clears the sticky Stop flag and recreates the server. */
    fun restart(context: Context) {
        val app = context.applicationContext
        setManuallyStopped(app, false)
        launch?.cancel()
        ensureScope()
        launch = scope.launch {
            mutex.withLock {
                stopLocked(app, restarting = true)
                if (state.value.serviceReady) {
                    Log.w(TAG, "Restart aborted: the previous Shizuku Binder is still alive")
                    return@withLock
                }
                if (AdbEngine.status.value.connected && AdbTransport.isConnected) startLocked(app)
                else state.value = Status(
                    message = "Reconnect authorized ADB UID 2000 to restart Shizuku.")
            }
        }
    }

    private fun serverDirectory(context: Context) =
        "/data/local/tmp/" + context.packageName + "-shizuku"

    private suspend fun stopLocked(context: Context, restarting: Boolean = false) {
        state.update { it.copy(starting = false, stopping = true,
            message = if (restarting) "Stopping old server for restart…" else "Stopping Shizuku server…") }
        Log.i(TAG, if (restarting) "Restart requested" else "Manual Stop requested")
        try {
            if (binderReady(context)) {
                runCatching { Shizuku.exit() }.onFailure {
                    Log.w(TAG, "Binder exit failed", it)
                }
            }
            // Only kill a process we previously started: its own private PID file
            // AND a unique process name must match. Do not kill an external Shizuku.
            if (AdbEngine.status.value.connected && AdbTransport.isConnected) {
                val dir = serverDirectory(context)
                // The native starter logs its detached server PID.
                // The prior app_process implementation stored server.pid.
                // Both PID and the original private CLASSPATH must match.
                val command = "pid=\$(sed -n " +
                    "'s/.*shizuku_server pid is \\([0-9][0-9]*\\).*/\\1/p' " +
                    dir + "/server.log 2>/dev/null | tail -n 1); " +
                    "oldpid=\$(cat " + dir + "/server.pid 2>/dev/null); " +
                    "for target in \"\$pid\" \"\$oldpid\"; do " +
                    "case \"\$target\" in ''|*[!0-9]*) continue ;; esac; " +
                    "cmd=\$(tr '\\000' ' ' < /proc/\$target/cmdline 2>/dev/null); " +
                    "case \"\$cmd\" in *shizuku_server*|*rikka.shizuku.server.ShizukuService*) " +
                    "envline=\$(tr '\\000' '\\n' < /proc/\$target/environ 2>/dev/null | " +
                    "grep -F 'CLASSPATH=" + dir + "/base.apk' || true); " +
                    "if [ -n \"\$envline\" ]; then kill \"\$target\" 2>/dev/null || true; fi " +
                    ";; esac; done; " +
                    "rm -f " + dir + "/server.pid " + dir + "/starter.pid"
                AdbTransport.exec(command, 8_000).onFailure {
                    Log.w(TAG, "PID stop command failed", it)
                }
            }
            repeat(12) {
                if (!binderReady(context)) return@repeat
                delay(250)
            }
            if (binderReady(context)) {
                state.value = Status(serviceReady = true, shellUid = Process.SHELL_UID,
                    manuallyStopped = manuallyStopped(context),
                    message = "Stop requested, but Shizuku Binder is still active.")
            } else {
                state.value = Status(manuallyStopped = manuallyStopped(context),
                    message = if (restarting) "Old server stopped; restarting…"
                    else "Stopped manually. Tap Restart to run again.")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.e(TAG, "Shizuku Stop failed", failure)
            state.value = Status(manuallyStopped = manuallyStopped(context),
                message = "Stop failed: " + failure.message.orEmpty().take(180))
        }
    }

    /** Only one operation at a time: protected by the same coroutine Mutex. */
    private suspend fun startLocked(app: Context) {
        lastAttemptMs = SystemClock.elapsedRealtime()
        val dir = serverDirectory(app)
        val file = dir + "/base.apk"
        try {
            ensureValidOwnership(app)
            refresh(app)
            if (state.value.serviceReady) return
            state.value = Status(starting = true,
                message = "Verifying ADB shell and extracting bundled server…")
            check(AdbEngine.status.value.connected && AdbTransport.isConnected &&
                AdbTransport.exec("id -u", 3_000).getOrNull()?.trim() == "2000") {
                "Connect authorized local ADB shell UID 2000 before starting Shizuku"
            }
            check(app.packageName.matches(Regex("[A-Za-z0-9_.]+"))) {
                "Invalid package name"
            }
            Log.i(TAG, "Verified UID 2000. Checking embedded server SHA-256.")
            val uri = "content://" + app.packageName + ".embedded-shizuku/embedded"
            val extraction = "mkdir -p " + dir + " && chmod 700 " + dir + " && " +
                "content read --uri " + uri + " > " + file + " && chmod 600 " + file +
                " && sha256sum " + file
            val digestResult = AdbTransport.exec(extraction, 55_000).getOrThrow()
            check(digestResult.lines().any { it.trim().startsWith(SERVER_SHA256 + " ") }) {
                "Shizuku payload checksum mismatch or extraction failed"
            }

            state.update { it.copy(message = "Preparing native Shizuku-Next starter…") }
            // The upstream native starter detaches the server, supplies the
            // hidden API flags, handles SELinux/cgroup checks and retries launch.
            // Calling app_process directly skipped that compatibility work.
            val starter = dir + "/libshizuku.so"
            val starterUri = "content://" + app.packageName +
                ".embedded-shizuku/starter"
            val prepare = "content read --uri " + starterUri + " > " + starter +
                " && chmod 700 " + starter + " && test -s " + starter +
                " && echo localadb_starter_ready"
            val ready = AdbTransport.exec(prepare, 60_000).getOrThrow()
            check(ready.lines().any { it.trim() == "localadb_starter_ready" }) {
                "Could not extract native Shizuku starter: " + ready.takeLast(180)
            }

            state.update { it.copy(message = "Launching Shizuku-Next native starter…") }
            Log.i(TAG, "Server digest verified; running native starter as a foreground shell command")
            // Shizuku-Next itself runs libshizuku.so as a shell command and
            // waits for that command to exit. Its NATIVE starter forks the
            // detached Java server. Backgrounding the starter a second time
            // hid its exit status and could leave our persistent shell waiting.
            val command = "LD_LIBRARY_PATH=" + dir + " " + starter +
                " --apk=" + file + " > " + dir + "/server.log 2>&1 < /dev/null; " +
                "localadb_rc=\$?; echo localadb_starter_exit:\$localadb_rc; " +
                "tail -n 20 " + dir + "/server.log 2>/dev/null"
            val startupOutput = AdbTransport.exec(command, 20_000).getOrThrow()
            Log.i(TAG, "Native starter returned: " + startupOutput.takeLast(1400))
            check(startupOutput.lines().any {
                it.trim() == "localadb_starter_exit:0"
            }) {
                "Native Shizuku starter failed: " + startupOutput.takeLast(1100)
            }

            val beganWaiting = SystemClock.elapsedRealtime()
            val deadline = beganWaiting + 60_000
            var lastReport = -1
            while (SystemClock.elapsedRealtime() < deadline) {
                if (binderReady(app)) {
                    state.value = Status(serviceReady = true, shellUid = Process.SHELL_UID,
                        message = "Bundled Shizuku server connected · UID 2000")
                    Log.i(TAG, "Native Shizuku Binder connected as UID 2000")
                    return
                }
                val seconds = ((SystemClock.elapsedRealtime() - beganWaiting) / 1000).toInt()
                if (seconds / 5 != lastReport) {
                    lastReport = seconds / 5
                    state.update { current ->
                        current.copy(message = "Waiting for Shizuku Binder (" +
                            seconds + "s / 60s)…")
                    }
                }
                delay(650)
            }
            error("Shizuku server did not deliver a working manager Binder after 60s")
        } catch (cancelled: CancellationException) {
            state.update { it.copy(starting = false,
                message = "Shizuku start was interrupted") }
            throw cancelled
        } catch (failure: Exception) {
            val detail = if (AdbEngine.status.value.connected &&
                AdbTransport.isConnected) readFailureDiagnostics(dir, app.packageName) else ""
            val message = (failure.localizedMessage ?: "Shizuku startup failed").take(240)
            Log.e(TAG, "Shizuku startup failed: " + message)
            if (detail.isNotBlank()) Log.e(TAG, "Server output: " + detail.takeLast(2400))
            state.value = Status(message = message, diagnostics = detail,
                manuallyStopped = manuallyStopped(app))
        } finally {
            state.update { it.copy(starting = false) }
        }
    }

    private suspend fun readFailureDiagnostics(dir: String, packageName: String): String {
        // Bounded. Never execute or trust commands from the log content.
        return AdbTransport.exec(
            "echo '--- Shizuku server output ---'; tail -n 30 " +
                dir + "/server.log 2>/dev/null; " +
                "echo '--- startup metadata ---'; ls -lh " + dir +
                "/base.apk " + dir + "/libshizuku.so 2>/dev/null; " +
                "echo '--- server phases ---'; tail -n 30 " +
                "/storage/emulated/0/Android/media/" +
                packageName + "/starter.log 2>/dev/null; " +
                "echo '--- app data starter phases ---'; tail -n 20 " +
                "/storage/emulated/0/Android/data/" + packageName +
                "/files/starter.log 2>/dev/null",
            5_000
        ).getOrNull().orEmpty().takeLast(5000).trim()
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

    /**
     * Quit must stop the embedded server *before* AdbEngine closes its shell
     * connection, even if binder handoff previously failed.
     */
    fun shutdown(context: Context) {
        launch?.cancel()
        launch = null
        // Time bounded; the regular Stop button provides fuller verification.
        runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(2_500L) {
                mutex.withLock { stopLocked(context.applicationContext) }
            }
        }
        scope.cancel()
        state.value = Status(message = "Stopped by Quit.")
    }
}

package dev.n2k02.localadb

import android.content.Context
import android.provider.Settings
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.time.Duration

/**
 * Keeps LocalAdb's Shizuku server alive and reachable. Adapted from JemRec
 * (Apache-2.0, https://github.com/jemcik/JemRec).
 *
 * The server runs in adbd's cgroup and dies whenever adbd restarts. adbd
 * restarts on Wi-Fi loss unless some ADB transport stays enabled: USB
 * debugging (`adb_enabled`, no cable needed) keeps one enabled, so it is the
 * off-Wi-Fi shield. Some phones' Settings app also writes adb_enabled=0 by
 * itself; [UsbDebuggingWatch] then wakes LocalAdb within about a second.
 */
internal object Resilience {
    private const val TAG = "LocalAdbResilience"
    private const val PREFS = "localadb_resilience"
    private const val KEEP_PAIRED = "keep_pairing_past_timeout"
    private const val SHIELD = "usb_debugging_shield"
    const val ADB_ENABLED = "adb_enabled"

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun keepPaired(c: Context) = prefs(c).getBoolean(KEEP_PAIRED, true)
    fun setKeepPaired(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEEP_PAIRED, on).apply()
    fun shield(c: Context) = prefs(c).getBoolean(SHIELD, true)
    fun setShield(c: Context, on: Boolean) = prefs(c).edit().putBoolean(SHIELD, on).apply()

    /** Writing the 1 that is already there leaves adbd (and Shizuku) running. */
    const val SHIELD_COMMAND = "settings put global $ADB_ENABLED 1 && echo LOCALADB_SHIELD_UP"

    /** Runs on every verified shell, after Shizuku start was requested. Never throws. */
    suspend fun onVerifiedShell(context: Context) {
        val app = context.applicationContext
        try {
            PairingTimeout.apply(app)
            if (shield(app)) {
                val up = AdbTransport.exec(SHIELD_COMMAND, 5_000).getOrNull()
                    ?.lineSequence()?.any { it.trim() == "LOCALADB_SHIELD_UP" } == true
                if (!up) Log.w(TAG, "could not keep USB debugging on as the off-Wi-Fi shield")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "resilience setup failed", error)
        }
    }

    data class Signals(val stopped: Boolean, val backgroundAllowed: Boolean,
                       val shieldOn: Boolean, val maySecureSettings: Boolean)

    fun shouldRevive(s: Signals) = !s.stopped && s.backgroundAllowed
    fun shouldRestoreShield(s: Signals) = shouldRevive(s) && s.shieldOn && s.maySecureSettings
}

/** Wakes LocalAdb the moment USB debugging changes. A trigger fires once; each run re-arms. */
class UsbDebuggingWatch(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        arm(app, ExistingWorkPolicy.APPEND_OR_REPLACE)
        val signals = Resilience.Signals(
            stopped = AdbSettings.stopped(app),
            backgroundAllowed = AdbSettings.backgroundRecoveryAllowed(app),
            shieldOn = Resilience.shield(app),
            maySecureSettings = app.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") ==
                android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
        if (!Resilience.shouldRevive(signals)) return Result.success()
        Log.i(TAG, "USB debugging changed; checking ADB and Shizuku")
        try {
            if (Resilience.shouldRestoreShield(signals) && readUsbDebugging(app) != true)
                Settings.Global.putInt(app.contentResolver, Resilience.ADB_ENABLED, 1)
            AdbEngine.start(app)
            val adb = SharedAdbHostAdapter.core(app)
            if (AdbdRestartGuard.mayRetry(adb.state.value.connected, NativeShizukuManager.serving(app)) &&
                adb.retry(auto = true)) {
                // onVerifiedShell starts Shizuku asynchronously; keep this run
                // (and so the process) alive until it answers, bounded.
                repeat(30) {
                    if (NativeShizukuManager.serving(app)) return Result.success()
                    NativeShizukuManager.maybeRecover(app)
                    delay(2_000)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "USB debugging watch could not recover", error)
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "LocalAdbResilience"
        private const val NAME = "localadb-usb-debugging-watch"
        internal val UPDATE_DELAY: Duration = Duration.ofMillis(500)
        internal val MAX_DELAY: Duration = Duration.ofSeconds(1)

        private fun readUsbDebugging(c: Context): Boolean? = try {
            Settings.Global.getString(c.contentResolver, Resilience.ADB_ENABLED)?.trim()?.let { it == "1" }
        } catch (_: SecurityException) { null }

        /** Any network: Android blocks loopback for restricted-bucket jobs that ask for none. */
        internal fun constraints(): Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .addContentUriTrigger(Settings.Global.getUriFor(Resilience.ADB_ENABLED), false)
            .setTriggerContentUpdateDelay(UPDATE_DELAY)
            .setTriggerContentMaxDelay(MAX_DELAY)
            .build()

        fun arm(context: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(NAME, policy,
                OneTimeWorkRequestBuilder<UsbDebuggingWatch>().setConstraints(constraints()).build())
        }

        fun cancel(context: Context) =
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(NAME)
    }
}

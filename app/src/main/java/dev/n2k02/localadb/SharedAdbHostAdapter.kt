package dev.n2k02.localadb

import android.content.Context
import android.content.pm.PackageManager
import dev.n2k02.adb.core.AdbCore
import dev.n2k02.adb.core.AdbCoreRegistry
import dev.n2k02.adb.core.AdbHostHooks
import dev.n2k02.adb.core.AdbIdentity as CoreIdentity
import dev.n2k02.adb.core.AdbIdentitySource
import dev.n2k02.adb.core.AdbOption
import dev.n2k02.adb.core.AdbPreferenceStore
import dev.n2k02.adb.core.LibAdbWire
import dev.n2k02.adb.core.LocalAdbDiscovery

/** Single authorized transport for LocalAdb, backed by the existing persisted key. */
internal object SharedAdbHostAdapter {
    @Volatile private var active: AdbCore? = null

    @Synchronized fun core(context: Context): AdbCore =
        active ?: create(context.applicationContext).also {
            active = it
            AdbCoreRegistry.install(it)
        }

    fun current(): AdbCore? = active

    /** Called only after stopping app-owned console, server and worker jobs. */
    suspend fun shutdown() {
        val previous = synchronized(this) { active.also { active = null } }
        previous?.let {
            AdbCoreRegistry.uninstall(it)
            it.quit()
        }
    }

    private fun create(context: Context): AdbCore {
        val app = context.applicationContext
        val identity = AdbIdentitySource { ctx ->
            val existing = AdbIdentity.getOrCreate(ctx)
            CoreIdentity(existing.privateKey, existing.certificate)
        }
        lateinit var adb: AdbCore
        val hooks = object : AdbHostHooks {
            override suspend fun onVerifiedShell() {
                // This callback runs outside the shared connection mutex.
                // StateFlow collection is asynchronous: publish verified UID
                // before Shizuku and permission bootstrap check legacy status.
                AdbEngine.publishVerified(adb)
                NativeShizukuManager.start(app)
                AdbPermissionBootstrap.configure(app)
            }

            override suspend fun onRestoreAfterRestartChanged(enabled: Boolean) {
                val pkg = app.packageName.takeIf { it.matches(Regex("[A-Za-z0-9_.]+")) }
                    ?: return
                val alreadyGranted = app.checkSelfPermission(
                    "android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED
                if (alreadyGranted == enabled) return
                val action = if (enabled) "grant" else "revoke"
                runCatching {
                    adb.execVerified("pm $action $pkg android.permission.WRITE_SECURE_SETTINGS", 10_000)
                }
            }
        }
        adb = AdbCore(LibAdbWire(app, identity, "LOCALADB"), Choices(app),
            LocalAdbDiscovery(app), hooks)
        return adb
    }

    private class Choices(private val app: Context) : AdbPreferenceStore {
        private val prefs = app.getSharedPreferences("localadb_options", Context.MODE_PRIVATE)
        override fun enabled(option: AdbOption) =
            AdbSettings.get(app, AdbSettings.Option.valueOf(option.name))
        override fun setEnabled(option: AdbOption, enabled: Boolean) {
            AdbSettings.set(app, AdbSettings.Option.valueOf(option.name), enabled)
            if (option == AdbOption.WATCHDOG) AdbRecoveryWorker.schedule(app)
        }
        override fun manuallyStopped() = AdbSettings.stopped(app)
        override fun setManuallyStopped(stopped: Boolean) = AdbSettings.setStopped(app, stopped)
        override fun lastTlsPort() = AdbSettings.tlsPort(app)
        override fun setLastTlsPort(port: Int) { AdbSettings.rememberVerified(app, port) }
        override fun lastVerifiedAt() = prefs.getLong("core_verified_at", 0L)
        override fun setLastVerifiedAt(timeMs: Long) {
            prefs.edit().putLong("core_verified_at", timeMs).apply()
        }
        override fun wasAuthorized() = AdbSettings.paired(app)
        override fun setAuthorized() { AdbSettings.rememberVerified(app) }
    }
}

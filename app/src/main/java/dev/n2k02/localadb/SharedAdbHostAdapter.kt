package dev.n2k02.localadb

import android.content.Context
import dev.n2k02.adb.core.AdbCore
import dev.n2k02.adb.core.AdbIdentity as CoreIdentity
import dev.n2k02.adb.core.AdbIdentitySource
import dev.n2k02.adb.core.AdbOption
import dev.n2k02.adb.core.AdbPreferenceStore
import dev.n2k02.adb.core.LibAdbWire
import dev.n2k02.adb.core.LocalAdbDiscovery

/** Migration factory; preserve existing ADB authorization and user options.
 * Instantiate ONLY after shutting down the legacy ADB coordinator. */
internal object SharedAdbHostAdapter {
    fun create(context: Context): AdbCore {
        val app = context.applicationContext
        val identity = AdbIdentitySource { ctx ->
            val existing = AdbIdentity.getOrCreate(ctx)
            CoreIdentity(existing.privateKey, existing.certificate)
        }
        return AdbCore(LibAdbWire(app, identity, "LOCALADB"), Choices(app), LocalAdbDiscovery(app))
    }

    private class Choices(private val app: Context) : AdbPreferenceStore {
        private val legacy = app.getSharedPreferences("localadb_options", Context.MODE_PRIVATE)
    override fun enabled(option: AdbOption) =
        AdbSettings.get(app, AdbSettings.Option.valueOf(option.name))
    override fun setEnabled(option: AdbOption, enabled: Boolean) {
        AdbSettings.set(app, AdbSettings.Option.valueOf(option.name), enabled)
    }
    override fun manuallyStopped() = AdbSettings.stopped(app)
    override fun setManuallyStopped(stopped: Boolean) = AdbSettings.setStopped(app, stopped)
    override fun lastTlsPort() = AdbSettings.tlsPort(app)
    override fun setLastTlsPort(port: Int) { AdbSettings.rememberVerified(app, port) }
    override fun lastVerifiedAt() = legacy.getLong("core_verified_at", 0L)
    override fun setLastVerifiedAt(timeMs: Long) {
        legacy.edit().putLong("core_verified_at", timeMs).apply()
    }
    override fun wasAuthorized() = AdbSettings.paired(app)
    override fun setAuthorized() { AdbSettings.rememberVerified(app) }
    }
}

package dev.n2k02.localadb

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/** Only a verified, user-authorized Android shell session may change debugging settings. */
internal object AdbSettings {
    enum class Option { AUTO_PAIR, WATCHDOG, START_ON_BOOT, RESTORE_AFTER_RESTART, TCP_5555 }
    private const val NAME = "localadb_options"
    private const val LAST_BOOT = "verified_boot"
    private const val TLS_BOOT = "tls_boot"
    private const val TLS_PORT = "tls_port"
    private const val PAIRED = "paired"
    private const val STOPPED = "user_quit"

    private fun key(option: Option) = option.name.lowercase()
    private fun defaults(option: Option) = option != Option.RESTORE_AFTER_RESTART
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    fun get(context: Context, option: Option) = prefs(context).getBoolean(key(option), defaults(option))
    fun set(context: Context, option: Option, enabled: Boolean) =
        prefs(context).edit().putBoolean(key(option), enabled).apply()
    private const val FOREGROUND_BOOT = "last_foreground_boot"

    /** A persisted watchdog cannot start ADB after reboot when boot start is OFF.
     * An explicit foreground launch enables recovery for the current boot only. */
    fun markForegroundOpened(context: Context) {
        val current = boot(context)
        if (current >= 0) prefs(context).edit().putInt(FOREGROUND_BOOT, current).apply()
    }

    internal fun backgroundRecoveryAllowed(
        startOnBoot: Boolean, currentBoot: Int, lastOpenedBoot: Int
    ): Boolean = startOnBoot || (currentBoot >= 0 && currentBoot == lastOpenedBoot)

    fun backgroundRecoveryAllowed(context: Context): Boolean =
        !stopped(context) && backgroundRecoveryAllowed(
            get(context, Option.START_ON_BOOT), boot(context),
            prefs(context).getInt(FOREGROUND_BOOT, -1)
        )

    fun stopped(context: Context) = prefs(context).getBoolean(STOPPED, false)
    fun setStopped(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(STOPPED, enabled).commit()
    }
    fun paired(context: Context) = prefs(context).getBoolean(PAIRED, false)
    fun markPaired(context: Context) = prefs(context).edit().putBoolean(PAIRED, true).apply()
    fun boot(context: Context): Int = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    }.getOrDefault(-1)
    fun rememberVerified(context: Context, tlsPort: Int? = null) {
        val b = boot(context)
        val e = prefs(context).edit().putBoolean(PAIRED, true)
        if (b >= 0) {
            e.putInt(LAST_BOOT, b)
            if (tlsPort != null && tlsPort in 1..65535) {
                e.putInt(TLS_BOOT, b).putInt(TLS_PORT, tlsPort)
            }
        }
        e.apply()
    }
    fun tlsPort(context: Context): Int? {
        val p = prefs(context)
        val b = boot(context)
        return p.getInt(TLS_PORT, -1).takeIf {
            it in 1..65535 && b >= 0 && b == p.getInt(TLS_BOOT, -1)
        }
    }
    fun canRestore(context: Context, wifiConnected: Boolean): RestorePolicy.Decision {
        val secure = context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") ==
            PackageManager.PERMISSION_GRANTED
        val enabled = runCatching {
            Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1
        }.getOrDefault(false)
        return RestorePolicy.decide(RestorePolicy.Input(
            optedIn = get(context, Option.RESTORE_AFTER_RESTART),
            paired = paired(context),
            stopped = stopped(context),
            supported = Build.VERSION.SDK_INT >= 30,
            permission = secure,
            wifiConnected = wifiConnected,
            wirelessEnabled = enabled,
            currentBoot = boot(context),
            verifiedBoot = prefs(context).getInt(LAST_BOOT, -1)
        ))
    }
}

internal object RestorePolicy {
    enum class Decision { NOT_ENABLED, NOT_PAIRED, STOPPED, UNSUPPORTED, NO_PERMISSION, NO_WIFI, ALREADY_ON, USER_DISABLED, RESTORE }
    data class Input(
        val optedIn: Boolean, val paired: Boolean, val stopped: Boolean,
        val supported: Boolean, val permission: Boolean, val wifiConnected: Boolean,
        val wirelessEnabled: Boolean, val currentBoot: Int, val verifiedBoot: Int
    )
    fun decide(i: Input): Decision = when {
        !i.optedIn -> Decision.NOT_ENABLED
        !i.paired -> Decision.NOT_PAIRED
        i.stopped -> Decision.STOPPED
        !i.supported -> Decision.UNSUPPORTED
        !i.permission -> Decision.NO_PERMISSION
        !i.wifiConnected -> Decision.NO_WIFI
        i.wirelessEnabled -> Decision.ALREADY_ON
        i.currentBoot < 0 || i.verifiedBoot < 0 || i.currentBoot == i.verifiedBoot -> Decision.USER_DISABLED
        else -> Decision.RESTORE
    }
}

/** Never scan another app's UI: only a visible Android Settings pairing dialog. */
internal object PairingCodePolicy {
    private val settingsPackages = setOf(
        "com.android.settings", "com.google.android.settings",
        "com.samsung.android.settings", "com.android.tv.settings"
    )
    private val code = Regex("[0-9]{6}")
    private val inline = Regex("(?<![0-9])[0-9]{6}(?![0-9])")
    fun isSystemSettings(name: String?) = name in settingsPackages
    fun extract(labels: List<String>): String? {
        if (labels.none { it.contains("pairing code", true) || it.contains("pair with device", true) || it.contains("pair device with", true) }) return null
        labels.asSequence().map(String::trim).firstOrNull(code::matches)?.let { return it }
        return labels.asSequence().filter { it.contains("pairing code", true) }
            .mapNotNull { inline.find(it)?.value }.firstOrNull()
    }
}

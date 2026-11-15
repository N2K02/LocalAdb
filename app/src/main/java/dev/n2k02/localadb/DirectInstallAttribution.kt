package dev.n2k02.localadb

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Default installer-of-record policy for direct, shell-initiated APK installs.
 * The recorded installing package is NOT proof of Google Play initiation.
 */
internal object DirectInstallAttribution {
    const val PLAY_PACKAGE = "com.android.vending"
    data class Source(
        val available: Boolean,
        val initiating: String? = null,
        val installing: String? = null,
        val failure: String? = null,
    )

    // No user-facing switch: use Play attribution when Play is available.
    // This only sets the installer-of-record, never claims genuine Play initiation.
    fun enabled(context: Context): Boolean = playInstalled(context)

    fun playInstalled(context: Context): Boolean = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(PLAY_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** Fail explicitly rather than silently ignoring the user's selected mode. */
    fun requestedInstaller(context: Context): String? {
        val preferred = enabled(context)
        val installed = if (preferred) playInstalled(context) else false
        return installerOption(preferred, installed)
    }

    /** Pure policy, shared by both direct-install entry points. */
    fun installerOption(preferPlay: Boolean, playInstalled: Boolean): String? {
        if (!preferPlay) return null
        return if (playInstalled) PLAY_PACKAGE else null
    }

    /** Fixed shell arguments; never interpolate installer names into a command string. */
    fun sessionCreateArgs(installer: String?): List<String> {
        require(installer == null || installer == PLAY_PACKAGE) {
            "Unsupported installer-of-record package"
        }
        return listOf("install-create", "-r", "-t") +
            if (installer == PLAY_PACKAGE) listOf("-i", PLAY_PACKAGE) else emptyList()
    }

    fun readBack(context: Context, packageName: String): Source = try {
        if (Build.VERSION.SDK_INT >= 30) {
            val source = context.packageManager.getInstallSourceInfo(packageName)
            Source(true, source.initiatingPackageName, source.installingPackageName)
        } else {
            @Suppress("DEPRECATION")
            val installing = context.packageManager.getInstallerPackageName(packageName)
            Source(true, null, installing)
        }
    } catch (failure: Exception) {
        Source(false, failure = failure.message ?: failure.javaClass.simpleName)
    }

    fun verdict(requested: String?, source: Source): String = when {
        !source.available -> "Installer source could not be verified."
        requested == null && source.installing == null -> "Android did not report an installer of record."
        requested == null -> "Installer source recorded by Android."
        source.installing == requested -> "Google Play recorded as installer of record (not Play initiation)."
        else -> "Requested Google Play installer was not recorded by Android."
    }

    fun sourceDetails(requested: String?, source: Source): String = buildString {
        append(verdict(requested, source))
        if (!source.available) {
            append(" Readback error: ")
            append(source.failure ?: "unavailable")
            return@buildString
        }
        append("\nRequested installer: ")
        append(requested ?: "System default")
        append("\nInstalling package: ")
        append(source.installing ?: "Not recorded")
        append("\nInitiating package: ")
        append(source.initiating ?: "Not recorded")
    }
}

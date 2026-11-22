package dev.n2k02.localadb

/** Interprets the OS readback as metadata only. */
internal object AndroidAutoInstallPolicy {
    const val VENDING = "com.android.vending"
    const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    const val INSTALL_ACTION = "android.intent.action.INSTALL_PACKAGE"

    enum class Path { SHELL, APP }

    enum class LaunchStatus { OPENED, REUSED, REJECTED, UNKNOWN }
    data class LaunchResult(val status: LaunchStatus, val detail: String)

    fun sessionFromUri(uri: String, authority: String): String? {
        val prefix = "content://$authority/"
        if (!uri.startsWith(prefix) || !uri.endsWith(".apk")) return null
        val session = uri.removePrefix(prefix).removeSuffix(".apk")
        return session.takeIf(ApkInstallPolicy::validSession)
    }

    // ActivityManager's hidden activity-start result constants.
    fun binderLaunchStatus(code: Int): LaunchStatus = when {
        code == 0 -> LaunchStatus.OPENED
        code == 2 || code == 3 -> LaunchStatus.REUSED
        code < 0 || code == 1 || code == 4 || code in 100..102 -> LaunchStatus.REJECTED
        else -> LaunchStatus.UNKNOWN
    }

    fun shellLaunchStatus(success: Boolean, output: String): LaunchStatus = when {
        output.contains("current activity is being kept", ignoreCase = true) ||
            output.contains("should be handled by the caller", ignoreCase = true) ||
            output.contains("Error:", ignoreCase = true) ||
            output.contains("SecurityException", ignoreCase = true) -> LaunchStatus.REJECTED
        !success || output.contains("Exception", ignoreCase = true) -> LaunchStatus.UNKNOWN
        output.contains("delivered to currently running", ignoreCase = true) ||
            output.contains("task has been brought to the front", ignoreCase = true) -> LaunchStatus.REUSED
        output.contains("Warning:", ignoreCase = true) -> LaunchStatus.UNKNOWN
        output.contains("Starting:", ignoreCase = true) -> LaunchStatus.OPENED
        else -> LaunchStatus.UNKNOWN
    }

    fun accepts(
        initiating: String?,
        installing: String?,
        sdk: Int,
        verifiedSystemInstallers: Set<String> = emptySet(),
    ): Boolean {
        if (installing != VENDING) return false
        if (sdk < 30) return true
        return initiating != null && initiating != "com.android.shell" &&
            (initiating == VENDING || initiating == "com.google.android.packageinstaller" ||
                initiating == "com.android.packageinstaller" || initiating in verifiedSystemInstallers)
    }

    fun isSelectedInstall(
        installedVersion: Long?, targetVersion: Long, updatedAt: Long, previousUpdate: Long,
        reportedSuccess: Boolean,
    ): Boolean = installedVersion == targetVersion &&
        (reportedSuccess || updatedAt > previousUpdate)

    /** The helper must also verify the component against Android's system installer resolver. */
    fun shellLaunchArgs(component: String, uri: String): List<String> {
        require(component.matches(Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+")))
        return listOf(
            "/system/bin/am", "start", "-n", component, "-a", INSTALL_ACTION,
            "-d", uri, "-t", APK_MIME_TYPE, "--grant-read-uri-permission",
            "--es", "android.intent.extra.INSTALLER_PACKAGE_NAME", VENDING,
            "--ez", "android.intent.extra.NOT_UNKNOWN_SOURCE", "true",
            "--ei", "android.content.pm.extra.INSTALL_REASON", "1",
            "--es", "android.intent.extra.REFERRER_NAME", "android-app://$VENDING",
        )
    }
}

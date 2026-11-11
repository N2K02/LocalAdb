package dev.n2k02.localadb

import java.util.UUID

/** Pure installation decisions, independently unit-tested without Android framework stubs. */
internal object ApkInstallPolicy {
    const val MAX_APK_BYTES = 512L * 1024 * 1024
    private val sessionPattern =
        Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    fun validSession(value: String): Boolean =
        sessionPattern.matches(value) &&
            runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

    fun validLength(bytes: Long): Boolean = bytes in 1..MAX_APK_BYTES

    fun installedChanged(previousVersion: Long?, previousUpdate: Long?,
        currentVersion: Long?, currentUpdate: Long?): Boolean {
        if (currentVersion == null || currentUpdate == null) return false
        return previousVersion == null || previousUpdate == null ||
            previousVersion != currentVersion || previousUpdate != currentUpdate
    }

    fun result(successResult: Boolean, changed: Boolean, cancelled: Boolean): String =
        when {
            successResult || changed -> "Installation completed"
            cancelled -> "Installation cancelled"
            else -> "Installation was not confirmed"
        }
}

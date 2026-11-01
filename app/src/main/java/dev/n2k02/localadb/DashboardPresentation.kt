package dev.n2k02.localadb

import java.util.Locale

/**
 * Only verified permission states are shown: never copy the mock's
 * placeholder Notifications/Storage/Phone permissions into LocalAdb.
 */
internal object DashboardPresentation {
    private val labels = mapOf(
        "android.permission.NEARBY_WIFI_DEVICES" to "Nearby Wi-Fi",
        "android.permission.ACCESS_LOCAL_NETWORK" to "Local network",
        "android.permission.WRITE_SECURE_SETTINGS" to "Secure settings",
    )

    fun permissionName(permission: String): String {
        labels[permission]?.let { return it }
        return permission.substringAfterLast('.').split('_')
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                word.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
            }.ifBlank { permission }
    }

    fun permissionSummary(finished: Boolean, granted: Set<String>, unavailable: Set<String>): String =
        if (!finished) "Checking verified Android permissions…"
        else granted.size.toString() + " granted · " + unavailable.size + " unavailable"

    fun permissionCoverage(finished: Boolean, grantedCount: Int, unavailableCount: Int): Float? {
        if (!finished || grantedCount < 0 || unavailableCount < 0) return null
        val total = grantedCount + unavailableCount
        return if (total > 0) grantedCount.toFloat() / total else null
    }
}

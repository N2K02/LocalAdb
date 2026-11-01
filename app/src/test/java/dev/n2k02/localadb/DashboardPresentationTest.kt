package dev.n2k02.localadb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardPresentationTest {
    @Test fun namesOnlyRealKnownLocalAdbPermissions() {
        assertEquals("Nearby Wi-Fi",
            DashboardPresentation.permissionName("android.permission.NEARBY_WIFI_DEVICES"))
        assertEquals("Local network",
            DashboardPresentation.permissionName("android.permission.ACCESS_LOCAL_NETWORK"))
        assertEquals("Secure settings",
            DashboardPresentation.permissionName("android.permission.WRITE_SECURE_SETTINGS"))
        assertEquals("Read Logs",
            DashboardPresentation.permissionName("android.permission.READ_LOGS"))
    }

    @Test fun permissionSummaryReflectsAndroidVerifiedReadback() {
        val granted = setOf("android.permission.ACCESS_LOCAL_NETWORK",
            "android.permission.NEARBY_WIFI_DEVICES")
        val unavailable = setOf("android.permission.WRITE_SECURE_SETTINGS")
        assertEquals("Checking verified Android permissions…",
            DashboardPresentation.permissionSummary(false, granted, unavailable))
        assertEquals("2 granted · 1 unavailable",
            DashboardPresentation.permissionSummary(true, granted, unavailable))
    }

    @Test fun coverageNeverFabricatesFullSuccessWhenDataMissing() {
        assertNull(DashboardPresentation.permissionCoverage(false, 3, 0))
        assertNull(DashboardPresentation.permissionCoverage(true, 0, 0))
        assertNull(DashboardPresentation.permissionCoverage(true, -1, 0))
        assertEquals(1f, DashboardPresentation.permissionCoverage(true, 3, 0)!!, 0.0001f)
        assertEquals(0.5f, DashboardPresentation.permissionCoverage(true, 2, 2)!!, 0.0001f)
    }

    @Test fun homePermissionChipsAlwaysUseVerifiedSets() {
        val screen = java.io.File(
            "src/main/java/dev/n2k02/localadb/LocalAdbScreen.kt").readText()
        val home = screen.substringAfter("private fun HomePage(")
            .substringBefore("private fun SettingsPage(")
        assertTrue(home.contains("permissions.granted.sorted()"))
        assertTrue(home.contains("permissions.unavailable.sorted()"))
        assertTrue(home.contains("DashboardPresentation.permissionName(permission)"))
        assertTrue(home.contains("DashboardPresentation.permissionCoverage("))
        assertTrue(!home.contains("Notifications\""))
        assertTrue(!home.contains("Storage\""))
        assertTrue(!home.contains("Phone\""))
    }

    @Test fun dashboardCountsComeFromLiveAppDiscovery() {
        val source = java.io.File(
            "src/main/java/dev/n2k02/localadb/LocalAdbScreen.kt").readText()
        val home = source.substringAfter("private fun HomePage(")
            .substringBefore("private fun SettingsPage(")
        assertTrue(home.contains("AppAccessRepository.list(context.applicationContext)"))
        assertTrue(home.contains("available.count { it.shizukuClient }"))
        assertTrue(home.contains("available.sumOf { it.grantedAdbPermissions.size }"))
    }
}

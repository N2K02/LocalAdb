package dev.n2k02.localadb

import org.junit.Assert.*
import org.junit.Test

class AppAccessPolicyTest {
    private val shizuku = AppAccess(
        "com.example.shizuku", "Shizuku Client", 10001,
        system = false, enabled = true, shizukuClient = true,
        declaredAdbPermissions = listOf("android.permission.DUMP"),
        grantedAdbPermissions = emptySet()
    )
    private val system = AppAccess(
        "com.example.system", "System Tools", 10002,
        system = true, enabled = false, shizukuClient = false,
        declaredAdbPermissions = listOf("android.permission.READ_LOGS"),
        grantedAdbPermissions = setOf("android.permission.READ_LOGS")
    )

    @Test fun detectsOnlyDeclaredShizukuClient() {
        assertTrue(AppAccessPolicy.isShizukuClient(
            listOf(AppAccessPolicy.SHIZUKU_PERMISSION), true))
        assertFalse(AppAccessPolicy.isShizukuClient(
            listOf(AppAccessPolicy.SHIZUKU_PERMISSION), false))
        assertFalse(AppAccessPolicy.isShizukuClient(
            listOf("android.permission.DUMP"), true))
    }

    @Test fun cannotGrantShizukuPermissionUsingAdb() {
        assertFalse(AppAccessPolicy.validTarget(
            shizuku.packageName, AppAccessPolicy.SHIZUKU_PERMISSION))
        assertFalse(AppAccessPolicy.validTarget(
            "com.example.app;whoami", "android.permission.DUMP"))
        assertFalse(AppAccessPolicy.validTarget(
            "com.example.app", "android.permission.RECORD_AUDIO"))
        assertTrue(AppAccessPolicy.validTarget(
            "com.example.app", "android.permission.DUMP"))
    }

    @Test fun onlyDeclaredDeveloperPermissionsAreEligible() {
        assertEquals(listOf("android.permission.DUMP"), AppAccessPolicy.onlyRequested(
            listOf("android.permission.RECORD_AUDIO", "android.permission.DUMP",
                AppAccessPolicy.SHIZUKU_PERMISSION, "android.permission.DUMP")))
    }

    @Test fun filtersAreRealAndroidGrantStatesNotShizukuGrantGuesses() {
        val apps = listOf(shizuku, system)
        assertEquals(listOf(shizuku),
            AppAccessPolicy.filter(apps, "", "User", true, false))
        assertEquals(listOf(system),
            AppAccessPolicy.filter(apps, "", "System", false, true))
        assertEquals(listOf(system),
            AppAccessPolicy.filter(apps, "tools", "Disabled", false, null))
        assertTrue(AppAccessPolicy.filter(apps, "missing", "All", false, null).isEmpty())
    }
}

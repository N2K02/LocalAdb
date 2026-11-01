package dev.n2k02.localadb

import android.content.pm.PermissionInfo
import org.junit.Assert.*
import org.junit.Test

class AppAccessPolicyTest {
    private val shizuku = AppAccess(
        "com.example.shizuku", "Shizuku Client", 10001,
        system = false, enabled = true, shizukuClient = true,
        declaredAdbPermissions = listOf("android.permission.DUMP"),
        grantedAdbPermissions = emptySet(),
        shizukuV3 = true,
        grantableAdbPermissions = setOf("android.permission.DUMP"),
        hasLauncher = true,
    )
    private val system = AppAccess(
        "com.example.system", "System Tools", 10002,
        system = true, enabled = false, shizukuClient = false,
        declaredAdbPermissions = listOf("android.permission.READ_LOGS"),
        grantedAdbPermissions = setOf("android.permission.READ_LOGS"),
        grantableAdbPermissions = setOf("android.permission.READ_LOGS"),
        hasLauncher = false,
    )

    @Test fun shizukuDetectionUsesDeclaredPermissionNotServerGrant() {
        val permission = listOf(AppAccessPolicy.SHIZUKU_PERMISSION)
        assertTrue(AppAccessPolicy.isShizukuClient(permission, true))
        // A legacy client might omit V3 metadata, but it still declares Shizuku API use.
        assertTrue(AppAccessPolicy.isShizukuClient(permission, false))
        assertFalse(AppAccessPolicy.isShizukuClient(listOf("android.permission.DUMP"), true))
        assertFalse(AppAccessPolicy.isShizukuClient(emptyList(), false))
    }

    @Test fun shizukuAuthorizationCannotBeGrantedByAdb() {
        assertFalse(AppAccessPolicy.validTarget(
            shizuku.packageName, AppAccessPolicy.SHIZUKU_PERMISSION))
        assertFalse(AppAccessPolicy.validTarget(
            "com.example.app;whoami", "android.permission.DUMP"))
        assertFalse(AppAccessPolicy.validTarget(
            "com.example.app && echo x", "android.permission.DUMP"))
        assertFalse(AppAccessPolicy.validTarget(
            "com.example.app", "android.permission.RECORD_AUDIO"))
        assertTrue(AppAccessPolicy.validTarget(
            "com.example.app", "android.permission.DUMP"))
    }

    @Test fun onlyExplicitDeveloperPermissionsAreCandidates() {
        assertEquals(
            listOf("android.permission.DUMP"),
            AppAccessPolicy.onlyRequested(listOf(
                "android.permission.RECORD_AUDIO", "android.permission.DUMP",
                AppAccessPolicy.SHIZUKU_PERMISSION, "android.permission.DUMP"
            ))
        )
    }

    @Test fun androidPermissionMustActuallyHaveDevelopmentProtectionFlag() {
        val dev = PermissionInfo.PROTECTION_SIGNATURE or
            PermissionInfo.PROTECTION_FLAG_DEVELOPMENT
        assertTrue(AppAccessPolicy.isDeveloperPermission(dev))
        assertFalse(AppAccessPolicy.isDeveloperPermission(PermissionInfo.PROTECTION_DANGEROUS))
        assertFalse(AppAccessPolicy.isDeveloperPermission(PermissionInfo.PROTECTION_SIGNATURE))
        assertFalse(AppAccessPolicy.isDeveloperPermission(0))
    }

    @Test fun onlyCurrentUserUidCanBeModified() {
        assertTrue(AppAccessPolicy.sameUser(10000, 12000))
        assertTrue(AppAccessPolicy.sameUser(1012345, 1010000))
        assertFalse(AppAccessPolicy.sameUser(10000, 110001))
        assertFalse(AppAccessPolicy.sameUser(1012345, 120001))
        assertFalse(AppAccessPolicy.sameUser(-1, 10000))
    }

    @Test fun installedManagerIsPreferredNotGuessed() {
        assertNull(AppAccessPolicy.preferredManager(emptyList()))
        assertEquals("moe.shizuku.privileged.api", AppAccessPolicy.preferredManager(
            setOf("moe.shizuku.manager", "moe.shizuku.privileged.api")))
        assertEquals("moe.shizuku.manager", AppAccessPolicy.preferredManager(
            setOf("moe.shizuku.manager")))
        assertNull(AppAccessPolicy.preferredManager(listOf("com.random.fake.shizuku")))
    }

    @Test fun androidAndShizukuFiltersRemainSeparate() {
        val apps = listOf(shizuku, system)
        assertEquals(listOf(shizuku),
            AppAccessPolicy.filter(apps, "", "User", true, false))
        assertEquals(listOf(system),
            AppAccessPolicy.filter(apps, "", "System", false, true))
        assertEquals(listOf(system),
            AppAccessPolicy.filter(apps, "tools", "Disabled", false, null))
        assertEquals(listOf(system),
            AppAccessPolicy.filter(apps, "", "Hidden", false, null))
        assertEquals(listOf(shizuku),
            AppAccessPolicy.filter(apps, "shizuku", "All", true, null))
        assertTrue(AppAccessPolicy.filter(apps, "missing", "All", false, null).isEmpty())
    }

    @Test fun multiSelectFiltersAreInclusiveWithinGroupAndConjunctiveAcrossGroups() {
        val apps = listOf(shizuku, system)
        assertEquals(listOf(shizuku), AppAccessPolicy.filterSelection(
            apps, "", emptySet(), setOf("Shizuku")))
        assertEquals(listOf(system), AppAccessPolicy.filterSelection(
            apps, "", setOf("System", "Hidden"), setOf("Android granted")))
        assertEquals(listOf(shizuku), AppAccessPolicy.filterSelection(
            apps, "", setOf("User", "Hidden"), setOf("Shizuku")))
        assertEquals(listOf(system), AppAccessPolicy.filterSelection(
            apps, "", emptySet(), setOf("Android granted", "Android revoked")))
        assertEquals(apps.size, AppAccessPolicy.filterSelection(
            apps, "", emptySet(), emptySet()).size)
        assertEquals(listOf(system), AppAccessPolicy.filterSelection(
            apps, "tools", emptySet(), emptySet()))
        assertTrue(AppAccessPolicy.filterSelection(
            apps, "nothing", emptySet(), emptySet()).isEmpty())
    }

    @Test fun descendingOrderIsStableAndReversesAscending() {
        val apps = listOf(system, shizuku)
        val ascending = AppAccessPolicy.filterSelection(
            apps, "", emptySet(), emptySet())
        assertEquals(ascending.asReversed(), AppAccessPolicy.filterSelection(
            apps, "", emptySet(), emptySet(), descending = true))
    }

    @Test fun repositoryOnlyUpdatesExplicitAndroidGrants() {
        val source = java.io.File(
            "src/main/java/dev/n2k02/localadb/AppAccessRepository.kt"
        ).readText()
        assertTrue(source.contains("require(AppAccessPolicy.validTarget"))
        assertTrue(source.contains("isGrantable(pm, permission)"))
        assertTrue(source.contains("id -u"))
        assertTrue(source.contains("\"2000\""))
        assertTrue(source.contains("pm.checkPermission(permission, packageName) == expected"))
        assertFalse(source.contains("updateFlagsForUid("))
        assertFalse(source.contains("grantRuntimePermission("))
    }

    @Test fun appPageRequiresConfirmationAndDoesNotInventShizukuGrantState() {
        val source = java.io.File(
            "src/main/java/dev/n2k02/localadb/AppsPage.kt"
        ).readText()
        assertTrue(source.contains("AlertDialog("))
        assertTrue(source.contains("NativeShizukuManager.granted(context, app.uid)"))
        assertTrue(source.contains("NativeShizukuManager.changeGrant("))
        assertTrue(source.contains("native.serviceReady"))
        assertFalse(source.contains("openShizukuManager"))
        assertTrue(source.contains("changeAndroidGrant("))
    }
}

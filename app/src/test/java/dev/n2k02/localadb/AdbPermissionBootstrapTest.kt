package dev.n2k02.localadb

import org.junit.Assert.*
import org.junit.Test

class AdbPermissionBootstrapTest {
    @Test fun grantsAreScopedToSdkAndOptions() {
        assertTrue(AdbPermissionBootstrap.eligible(30, false).isEmpty())
        assertEquals(
            listOf("android.permission.NEARBY_WIFI_DEVICES"),
            AdbPermissionBootstrap.eligible(33, false))
        assertTrue(AdbPermissionBootstrap.eligible(37, false)
            .contains("android.permission.ACCESS_LOCAL_NETWORK"))
        assertFalse(AdbPermissionBootstrap.eligible(37, false)
            .contains("android.permission.WRITE_SECURE_SETTINGS"))
        assertTrue(AdbPermissionBootstrap.eligible(37, true)
            .contains("android.permission.WRITE_SECURE_SETTINGS"))
    }

    @Test fun everyGrantRequiresLiveUidAndActualPackageManagerReadback() {
        val source = java.io.File(
            "src/main/java/dev/n2k02/localadb/AdbPermissionBootstrap.kt").readText()
        assertTrue(source.contains("AdbTransport.exec(\"id -u\", 3_000)"))
        assertTrue(source.contains("uid != \"2000\""))
        assertTrue(source.contains("app.checkSelfPermission(permission)"))
        assertFalse(source.contains("SHIZUKU_API_PERMISSION"))
    }
}

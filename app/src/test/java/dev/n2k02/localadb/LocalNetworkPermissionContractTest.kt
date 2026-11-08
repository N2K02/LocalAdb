package dev.n2k02.localadb

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class LocalNetworkPermissionContractTest {
    @Test fun android17RequiresRuntimeNearbyDevicesApprovalBeforeAdbStarts() {
        val base = listOf(File("app/src/main/java/dev/n2k02/localadb"),
            File("src/main/java/dev/n2k02/localadb")).first { it.isDirectory }
        val activity = base.resolve("MainActivity.kt").readText()
        assertTrue(activity.contains("AdbNetworkAccess.PERMISSION"))
        assertTrue(activity.contains("ActivityResultContracts.RequestPermission()"))
        assertTrue(activity.contains("AdbNetworkAccess.granted(this)"))
        assertTrue(activity.contains("AdbNetworkAccess.granted(this)"))
        assertTrue(activity.contains("if (canUseLocalNetwork())"))
        assertTrue(activity.contains("requestLocalNetwork.launch(localNetworkPermission)"))
        assertTrue(activity.contains("AdbEngine.start(applicationContext, explicit = freshLaunch)"))
        assertTrue(activity.contains("freshLaunch = savedInstanceState == null"))
        assertTrue(activity.contains("if (freshLaunch) AdbSettings.markForegroundOpened(applicationContext)"))
        assertFalse(activity.contains("super.onCreate(savedInstanceState)\n        AdbEngine.start("))
    }

    @Test fun permissionIsDeclaredButCannotBeAssumedGranted() {
        val manifest = listOf(File("app/src/main/AndroidManifest.xml"),
            File("src/main/AndroidManifest.xml")).first { it.isFile }.readText()
        assertTrue(manifest.contains("android.permission.ACCESS_LOCAL_NETWORK"))
        assertTrue(manifest.contains("android.permission.CHANGE_WIFI_MULTICAST_STATE"))
    }
}

package dev.n2k02.localadb

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class SharedAutoPairingContractTest {
    @Test fun noAppOwnedPairingCodeReaderOrManualPairPath() {
        val root = listOf(File("app/src/main/java/dev/n2k02/localadb"),
            File("src/main/java/dev/n2k02/localadb")).first { it.isDirectory }
        val reader = root.resolve("AdbPairingAccessibilityService.kt").readText()
        assertTrue(reader.contains("SharedAdbPairingAccessibilityService()"))
        assertTrue(reader.contains("SharedAdbHostAdapter.core(applicationContext)"))
        assertFalse(reader.contains("rootInActiveWindow"))
        assertFalse(reader.contains("extract("))
        assertFalse(reader.contains("PairingCodePolicy"))
        val engine = root.resolve("AdbEngine.kt").readText()
        assertFalse(engine.contains("submitPairingCode("))
        val home = root.resolve("AdbHomeSetupCard.kt").readText()
        assertTrue(home.contains("AdbHomeSetupPage(adb, accessibilityEnabled"))
        assertFalse(home.contains("Manual pairing (fallback)"))
    }

    @Test fun Android17GrantCheckUsesSharedLibraryPolicy() {
        val root = listOf(File("app/src/main/java/dev/n2k02/localadb"),
            File("src/main/java/dev/n2k02/localadb")).first { it.isDirectory }
        val main = root.resolve("MainActivity.kt").readText()
        assertTrue(main.contains("AdbNetworkAccess.PERMISSION"))
        assertTrue(main.contains("AdbNetworkAccess.granted(this)"))
        assertTrue(main.contains("requestLocalNetwork.launch(localNetworkPermission)"))
    }
}

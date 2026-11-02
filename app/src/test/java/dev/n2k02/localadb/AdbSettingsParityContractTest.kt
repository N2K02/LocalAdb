package dev.n2k02.localadb

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AdbSettingsParityContractTest {
    private fun code(name: String): String {
        val path = "app/src/main/java/dev/n2k02/localadb/" + name
        return listOf(File(path), File("../" + path)).first { it.isFile }.readText()
    }

    @Test fun settingsContainsOnlyVrecPairingAndSecurityCards() {
        val settings = code("AdbConnectionPage.kt")
        assertEquals(2, settings.split("Card(Modifier.fillMaxWidth())").size - 1)
        for (text in listOf("Pairing & recovery", "Connection & security",
            "Auto-detect pairing code", "Connection watchdog", "Start on boot",
            "Restore after restart", "Keep ADB without Wi-Fi (TCP/5555)",
            "Disable TCP/5555", "Try to restore the helper after restarting and unlocking.",
            "Requires Android permission; never overrides a manual off setting."))
            assertTrue(text, settings.contains(text))
        assertFalse(settings.contains("Connection status"))
        assertFalse(settings.contains("Pair with this phone"))
        assertFalse(settings.contains("Six-digit Android pairing code"))
        assertFalse(settings.contains("Open Accessibility settings"))
    }

    @Test fun pairingAndRetryAreOnHomeNotInSettings() {
        val home = code("LocalAdbScreen.kt")
        val setup = code("AdbHomeSetupCard.kt")
        assertTrue(home.contains("AdbHomeSetupCard()"))
        assertTrue(setup.contains("Find / Retry"))
        assertTrue(setup.contains("Pair with this phone"))
        assertTrue(setup.contains("AdbEngine.submitPairingCode"))
        assertTrue(setup.contains("AdbEngine.openDeveloperOptions"))
    }

    @Test fun healthyConnectionAndUid2000VerificationRequiredForRecovery() {
        val transport = code("AdbTransport.kt")
        assertTrue(transport.contains("lastKnownConnected = mgr.isConnected"))
        assertTrue(transport.contains("lastKnownConnected = true"))
        val engine = code("AdbEngine.kt")
        assertTrue(engine.contains("if (connectTcp(app)) return@withLock"))
        assertTrue(engine.contains("if (!isLocalPortListening()) return false"))
        assertTrue(engine.contains("verifyShell()"))
        assertTrue(engine.contains("\"2000\""))
        assertTrue(engine.contains("AdbSettings.Option.TCP_5555"))
    }
}

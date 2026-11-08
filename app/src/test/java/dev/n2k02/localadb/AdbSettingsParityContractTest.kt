package dev.n2k02.localadb

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AdbSettingsParityContractTest {
    private fun code(name: String): String {
        val path = "app/src/main/java/dev/n2k02/localadb/" + name
        return listOf(File(path), File("../" + path)).first { it.isFile }.readText()
    }

    @Test fun settingsUsesOneCanonicalSharedPage() {
        val settings = code("AdbConnectionPage.kt")
        assertTrue(settings.contains("AdbConnectionSettingsPage("))
        assertTrue(settings.contains("SharedAdbHostAdapter.core(context)"))
        assertTrue(settings.contains("AdbPairingAccessibilityService"))
        assertTrue(settings.contains("Settings.ACTION_ACCESSIBILITY_SETTINGS"))
        assertFalse(settings.contains("Pairing & recovery"))
        assertFalse(settings.contains("Connection & security"))
        assertFalse(settings.contains("private fun ToggleLine("))
    }

    @Test fun pairingAndRetryLiveOnHomeSharedPage() {
        assertTrue(code("LocalAdbScreen.kt").contains("AdbHomeSetupCard()"))
        val setup = code("AdbHomeSetupCard.kt")
        assertTrue(setup.contains("AdbPageLifecycle(adb)"))
        assertTrue(setup.contains("AdbHomeSetupPage(adb, accessibilityEnabled"))
        assertTrue(setup.contains("state.connected"))
    }

    @Test fun shellStatusRequiresVerifiedSharedUid2000() {
        val transport = code("AdbTransport.kt")
        assertTrue(transport.contains("shared().execVerified(command, timeoutMs)"))
        assertTrue(transport.contains("shared().execVerified(AdbConsoleProtocol.wrap(validated, marker), timeoutMs)"))
        assertFalse(transport.contains("AbsAdbConnectionManager"))
        val engine = code("AdbEngine.kt")
        assertTrue(engine.contains("shellUid = if (s.connected) 2000 else null"))
        assertTrue(engine.contains("s.transport == AdbTransportMode.LOCAL_TCP_5555"))
        assertTrue(engine.contains("AdbPhase.VERIFIED -> Phase.CONNECTED"))
        val adapter = code("SharedAdbHostAdapter.kt")
        assertTrue(adapter.contains("AdbSettings.tlsPort(app)"))
        assertTrue(adapter.contains("AdbSettings.paired(app)"))
    }
}

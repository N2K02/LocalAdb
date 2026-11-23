package dev.n2k02.localadb

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** adbd-restart guard, pairing expiry, off-Wi-Fi shield and USB debugging watch. */
class ShizukuResilienceTest {
    private fun code(name: String): String = listOf(
        File("src/main/java/dev/n2k02/localadb/$name"),
        File("app/src/main/java/dev/n2k02/localadb/$name"),
    ).first { it.isFile }.readText()

    @Test fun retryNeverRestartsAdbdUnderARunningShizuku() {
        assertFalse(AdbdRestartGuard.mayRetry(sessionVerified = true, shizukuServing = true))
        assertTrue(AdbdRestartGuard.mayRetry(sessionVerified = true, shizukuServing = false))
        assertTrue(AdbdRestartGuard.mayRetry(sessionVerified = false, shizukuServing = true))
        assertTrue(AdbdRestartGuard.mayRetry(sessionVerified = false, shizukuServing = false))
        val retry = code("AdbEngine.kt").substringAfter("fun retry(context: Context)")
            .substringBefore("fun preferencesChanged(")
        assertTrue(retry.contains("AdbdRestartGuard.mayRetry("))
        assertTrue(retry.contains("NativeShizukuManager.serving(app)"))
    }

    @Test fun pairingOutcomesParseExactLinesOnly() {
        assertEquals(PairingTimeout.Outcome.KEPT, PairingTimeout.parse("a\nLOCALADB_PAIRING_KEPT\n"))
        assertEquals(PairingTimeout.Outcome.ALREADY_OFF, PairingTimeout.parse("LOCALADB_PAIRING_ALREADY_OFF"))
        assertEquals(PairingTimeout.Outcome.GIVEN_BACK, PairingTimeout.parse("LOCALADB_PAIRING_GIVEN_BACK"))
        assertEquals(PairingTimeout.Outcome.NOTHING_TO_GIVE_BACK, PairingTimeout.parse("LOCALADB_PAIRING_NOTHING"))
        assertEquals(PairingTimeout.Outcome.FAILED, PairingTimeout.parse(null))
        assertEquals(PairingTimeout.Outcome.FAILED, PairingTimeout.parse("echo LOCALADB_PAIRING_KEPT x"))
    }

    @Test fun pairingCommandsRecordFirstAndNeverRunRecordedText() {
        val keep = PairingTimeout.keepCommand()
        assertTrue(keep.indexOf("> ${PairingTimeout.RECORD}") <
            keep.indexOf("settings put global adb_allowed_connection_time 0"))
        assertTrue(keep.contains("[ -e ${PairingTimeout.RECORD} ] ||"))
        val back = PairingTimeout.giveBackCommand()
        assertTrue(back.contains("''|*[!0-9]*) v=null"))
        assertTrue(back.contains("= 0 ]; then if [ \"\$v\" = null ]"))
        assertFalse(back.contains("eval"))
        assertFalse(keep.contains("\n") || back.contains("\n"))
    }

    @Test fun verifiedShellAppliesPairingAndShieldAfterShizukuStart() {
        val hook = code("SharedAdbHostAdapter.kt").substringAfter("override suspend fun onVerifiedShell()")
            .substringBefore("override suspend fun onRestoreAfterRestartChanged(")
        assertTrue(hook.indexOf("NativeShizukuManager.start(app)") < hook.indexOf("Resilience.onVerifiedShell(app)"))
        assertEquals("settings put global adb_enabled 1 && echo LOCALADB_SHIELD_UP", Resilience.SHIELD_COMMAND)
    }

    private fun s(stopped: Boolean = false, bg: Boolean = true, shield: Boolean = true, secure: Boolean = true) =
        Resilience.Signals(stopped, bg, shield, secure)

    @Test fun watchRespectsQuitAndBackgroundPolicy() {
        assertTrue(Resilience.shouldRevive(s()))
        assertFalse(Resilience.shouldRevive(s(stopped = true)))
        assertFalse(Resilience.shouldRevive(s(bg = false)))
        assertTrue(Resilience.shouldRestoreShield(s()))
        assertFalse(Resilience.shouldRestoreShield(s(shield = false)))
        assertFalse(Resilience.shouldRestoreShield(s(secure = false)))
        assertFalse(Resilience.shouldRestoreShield(s(stopped = true)))
    }

    @Test fun watchIsArmedReArmedAndCancelledOnQuit() {
        val watch = code("Resilience.kt")
        val work = watch.substringAfter("override suspend fun doWork()").substringBefore("companion object")
        assertTrue(work.indexOf("arm(app, ExistingWorkPolicy.APPEND_OR_REPLACE)") < work.indexOf("shouldRevive("))
        assertTrue(watch.contains("addContentUriTrigger(Settings.Global.getUriFor(Resilience.ADB_ENABLED), false)"))
        assertEquals(500L, UsbDebuggingWatch.UPDATE_DELAY.toMillis())
        assertEquals(1_000L, UsbDebuggingWatch.MAX_DELAY.toMillis())
        val engine = code("AdbEngine.kt")
        assertTrue(engine.contains("UsbDebuggingWatch.arm(app)"))
        assertTrue(engine.substringAfter("suspend fun shutdown(").contains("UsbDebuggingWatch.cancel(app)"))
    }

    @Test fun statusText() {
        assertEquals("Pairing will not expire.", ResilienceText.pairing(true, PairingTimeout.Outcome.KEPT))
        assertNull(ResilienceText.pairing(true, null))
        assertNull(ResilienceText.pairing(false, PairingTimeout.Outcome.ALREADY_OFF))
    }
}

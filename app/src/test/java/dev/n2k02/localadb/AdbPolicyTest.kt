package dev.n2k02.localadb

import org.junit.Assert.*
import org.junit.Test

class AdbPolicyTest {
    private fun ready() = RestorePolicy.Input(
        optedIn = true, paired = true, stopped = false, supported = true,
        permission = true, wifiConnected = true, wirelessEnabled = false,
        currentBoot = 16, verifiedBoot = 15
    )

    @Test fun activityRecreationCannotClearAdbStop() {
        val activity = listOf(java.io.File("app/src/main/java/dev/n2k02/localadb/MainActivity.kt"),
            java.io.File("../app/src/main/java/dev/n2k02/localadb/MainActivity.kt"))
            .first { it.isFile }.readText()
        assertTrue(activity.contains("freshLaunch = savedInstanceState == null"))
        assertTrue(activity.contains("if (freshLaunch) AdbSettings.markForegroundOpened(applicationContext)"))
        assertTrue(activity.contains("explicit = freshLaunch"))
    }

    @Test fun bootStartupIsIndependentOfPeriodicWatchdog() {
        assertTrue(AdbWorkerStartPolicy.shouldStart(true, false))
        assertTrue(AdbWorkerStartPolicy.shouldStart(true, true))
        assertTrue(AdbWorkerStartPolicy.shouldStart(false, true))
        assertFalse(AdbWorkerStartPolicy.shouldStart(false, false))
    }
    @Test fun disabledBootStartOnlyRecoversAfterForegroundOpenInSameBoot() {
        assertFalse(AdbSettings.backgroundRecoveryAllowed(false, 17, 16))
        assertFalse(AdbSettings.backgroundRecoveryAllowed(false, 17, -1))
        assertFalse(AdbSettings.backgroundRecoveryAllowed(false, -1, -1))
        assertTrue(AdbSettings.backgroundRecoveryAllowed(false, 17, 17))
        assertTrue(AdbSettings.backgroundRecoveryAllowed(true, 17, -1))
    }
    @Test fun onlyRestoreAfterBootChangeAndExplicitOptIn() {
        assertEquals(RestorePolicy.Decision.RESTORE, RestorePolicy.decide(ready()))
        assertEquals(RestorePolicy.Decision.NOT_ENABLED, RestorePolicy.decide(ready().copy(optedIn = false)))
    }
    @Test fun manualDisableSameBootNeverOverridden() {
        assertEquals(RestorePolicy.Decision.USER_DISABLED, RestorePolicy.decide(ready().copy(verifiedBoot = 16)))
        assertEquals(RestorePolicy.Decision.USER_DISABLED, RestorePolicy.decide(ready().copy(verifiedBoot = -1)))
        assertEquals(RestorePolicy.Decision.USER_DISABLED, RestorePolicy.decide(ready().copy(currentBoot = -1)))
    }
    @Test fun safeFailureStates() {
        assertEquals(RestorePolicy.Decision.NOT_PAIRED, RestorePolicy.decide(ready().copy(paired = false)))
        assertEquals(RestorePolicy.Decision.STOPPED, RestorePolicy.decide(ready().copy(stopped = true)))
        assertEquals(RestorePolicy.Decision.NO_WIFI, RestorePolicy.decide(ready().copy(wifiConnected = false)))
        assertEquals(RestorePolicy.Decision.NO_PERMISSION, RestorePolicy.decide(ready().copy(permission = false)))
        assertEquals(RestorePolicy.Decision.ALREADY_ON, RestorePolicy.decide(ready().copy(wirelessEnabled = true)))
        assertEquals(RestorePolicy.Decision.UNSUPPORTED, RestorePolicy.decide(ready().copy(supported = false)))
    }
    @Test fun pairingReadsOnlySettings() {
        assertTrue(PairingCodePolicy.isSystemSettings("com.android.settings"))
        assertTrue(PairingCodePolicy.isSystemSettings("com.samsung.android.settings"))
        assertFalse(PairingCodePolicy.isSystemSettings("com.whatsapp"))
        assertFalse(PairingCodePolicy.isSystemSettings(null))
    }
    @Test fun pairingRequiresDialogMarker() {
        assertNull(PairingCodePolicy.extract(listOf("06:45", "123456")))
        assertNull(PairingCodePolicy.extract(listOf("Pairing code", "12345")))
        assertNull(PairingCodePolicy.extract(listOf("Pairing code", "1234567")))
    }
    @Test fun pairingCanReadCodeNodeAndInlineLabel() {
        assertEquals("123456", PairingCodePolicy.extract(listOf("Pair device with pairing code", "123456")))
        assertEquals("123456", PairingCodePolicy.extract(listOf("Wi-Fi pairing code: 123456")))
        assertNull(PairingCodePolicy.extract(listOf("Secret 123456", "Other dialog")))
    }
}

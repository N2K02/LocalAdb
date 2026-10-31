package dev.n2k02.localadb

import android.Manifest
import org.junit.Assert.*
import org.junit.Test

class AdbAutomationPolicyTest {
    @Test fun automaticPermissionBootstrapIsVersionAware() {
        assertTrue(AdbPermissionBootstrap.eligiblePermissions(30, false).isEmpty())
        assertEquals(
            setOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.NEARBY_WIFI_DEVICES),
            AdbPermissionBootstrap.eligiblePermissions(33, false).toSet()
        )
        assertTrue(AdbPermissionBootstrap.eligiblePermissions(37, false)
            .contains("android.permission.ACCESS_LOCAL_NETWORK"))
        assertFalse(AdbPermissionBootstrap.eligiblePermissions(37, false)
            .contains(Manifest.permission.WRITE_SECURE_SETTINGS))
        assertTrue(AdbPermissionBootstrap.eligiblePermissions(37, true)
            .contains(Manifest.permission.WRITE_SECURE_SETTINGS))
    }
    @Test fun recoveryBackoffLimitsRepeatedAutomaticConnections() {
        val backoff = AdbRecoveryBackoff(1000, 8000)
        assertTrue(backoff.allowed(1))
        assertEquals(0L, backoff.delayFor(0))
        assertEquals(1000L, backoff.delayFor(1))
        assertEquals(2000L, backoff.delayFor(2))
        assertEquals(8000L, backoff.delayFor(10))
        backoff.onFailure(100)
        assertFalse(backoff.allowed(1099))
        assertTrue(backoff.allowed(1100))
        backoff.onFailure(1100)
        assertFalse(backoff.allowed(2000))
        backoff.onSuccess()
        assertEquals(0, backoff.failures)
        assertTrue(backoff.allowed(0))
    }
    @Test fun backoffAtMaximumLongCannotOverflow() {
        val backoff = AdbRecoveryBackoff(1000, 8000)
        backoff.onFailure(Long.MAX_VALUE - 10)
        assertFalse(backoff.allowed(0))
        assertTrue(backoff.allowed(Long.MAX_VALUE))
    }
    @Test fun quitStopsAllOwnedOperationsContract() {
        // Source-level contract complements the pure policy tests without an Android emulator.
        val source = java.io.File("src/main/java/dev/n2k02/localadb/AdbEngine.kt").readText()
        val quit = source.substringAfter("fun shutdown(context: Context)")
        assertTrue(quit.contains("AdbSettings.setStopped(context, true)"))
        assertTrue(quit.contains("scope.cancel()"))
        assertTrue(quit.contains("AdbTransport.close()"))
        assertTrue(quit.contains("AdbRecoveryWorker.cancel(context)"))
    }
    @Test fun bootRecoveryIsIndependentOfWatchdog() {
        val source = java.io.File("src/main/java/dev/n2k02/localadb/AdbRecoveryWorker.kt").readText()
        assertTrue(source.contains("inputData.getBoolean(\"boot\", false) ||"))
        assertTrue(source.contains("AdbEngine.start(app)"))
    }
}

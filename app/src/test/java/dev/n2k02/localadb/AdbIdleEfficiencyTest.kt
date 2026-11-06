package dev.n2k02.localadb

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class AdbIdleEfficiencyTest {
    private fun source() = listOf(
        File("src/main/java/dev/n2k02/localadb/AdbEngine.kt"),
        File("app/src/main/java/dev/n2k02/localadb/AdbEngine.kt")
    ).first { it.exists() }.readText()

    @Test fun watchdogOnlyRunsWhenUserEnabledIt() {
        val s = source()
        assertTrue(s.contains("if (AdbSettings.get(app, AdbSettings.Option.WATCHDOG)) startWatchdog(app)"))
        assertTrue(s.contains("if (!active || !AdbSettings.get(context, AdbSettings.Option.WATCHDOG))"))
        assertTrue(s.contains("watchdog?.cancel()"))
        assertTrue(s.contains("watchdog = null"))
        assertTrue(s.contains("while (isActive && active && !AdbSettings.stopped(context))"))
    }

    @Test fun discoveryTimeoutHasOneOwnerAndStopsWithDiscovery() {
        val s = source()
        assertTrue(s.contains("private var discoveryTimeoutJob: Job? = null"))
        assertTrue(s.contains("discoveryTimeoutJob = scope.launch {"))
        val cleanup = s.substringAfter("private fun stopDiscovery()")
            .substringBefore("private fun startWatchdog(")
        assertTrue(cleanup.contains("discoveryTimeoutJob?.cancel()"))
        assertTrue(cleanup.contains("wifiLock?.release()"))
        assertTrue(cleanup.contains("mgr.stopServiceDiscovery(listener)"))
        assertTrue(s.contains("scope.cancel()"))
        assertTrue(s.contains("AdbRecoveryWorker.cancel(app)"))
        assertTrue(s.contains("AdbTransport.close()"))
    }
}

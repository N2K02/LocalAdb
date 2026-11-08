package dev.n2k02.localadb

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Regression checks for the two independent privilege readiness gates. */
class ShizukuUidReadinessContractTest {
    private fun source(name: String) =
        File("src/main/java/dev/n2k02/localadb/$name.kt").readText()

    @Test fun nativeServerMustWaitForVerifiedAdbBeforeLaunching() {
        val s = source("NativeShizukuManager")
        assertTrue(s.contains("Waiting for verified ADB shell UID 2000 before starting Shizuku"))
        assertTrue(s.contains("Waiting for verified ADB shell UID 2000 before restarting Shizuku"))
        assertTrue(s.contains("AdbEngine.status.value.connected || !AdbTransport.isConnected"))
        assertTrue(s.contains("AdbTransport.exec(\"id -u\", 3_000)"))
        assertTrue(s.indexOf("if (!AdbEngine.status.value.connected || !AdbTransport.isConnected) {",
            s.indexOf("fun restart(context")) <
            s.indexOf("launch?.cancel()", s.indexOf("fun restart(context")))
    }

    @Test fun helperWaitsForAdbAndShizukuUidAndClosesLateCallbacks() {
        val s = source("LocalHelperDiagnostics")
        assertTrue(s.contains("AdbEngine.status.first { it.connected && AdbTransport.isConnected }"))
        assertTrue(s.contains("NativeShizukuManager.state.value.serviceReady"))
        assertTrue(s.contains("Shizuku.pingBinder() && Shizuku.getUid() == 2000"))
        assertTrue(s.indexOf("check(managerReady)") < s.indexOf("LocalHelperProbe.run("))
        assertTrue(s.contains("if (closed) return"))
        assertTrue(s.contains("if (!closed) connected.completeExceptionally"))
        assertTrue(s.contains("Shizuku.unbindUserService(args, callback, true)"))
    }
}

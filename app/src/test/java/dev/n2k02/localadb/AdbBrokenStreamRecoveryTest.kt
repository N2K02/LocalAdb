package dev.n2k02.localadb

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Prevent any second app-owned ADB transport after cutover. */
class AdbBrokenStreamRecoveryTest {
    private fun source(name: String) = listOf(
        File("src/main/java/dev/n2k02/localadb/" + name),
        File("app/src/main/java/dev/n2k02/localadb/" + name)
    ).first { it.isFile }.readText()

    @Test fun onlySharedCoreOwnsShellAndRecovery() {
        val transport = source("AdbTransport.kt")
        assertTrue(transport.contains("shared().execVerified(command, timeoutMs)"))
        assertFalse(transport.contains("AbsAdbConnectionManager"))
        assertFalse(transport.contains("AdbStream"))
        assertFalse(transport.contains("private var manager"))
        assertTrue(transport.contains("no legacy transport fallback"))
        val engine = source("AdbEngine.kt")
        assertFalse(engine.contains("NsdManager"))
        assertFalse(engine.contains("AdbTransport.connect("))
        assertTrue(engine.contains("SharedAdbHostAdapter.core(app)"))
        val adapter = source("SharedAdbHostAdapter.kt")
        assertTrue(adapter.contains("LibAdbWire(app, identity"))
        assertTrue(adapter.contains("LocalAdbDiscovery(app)"))
    }

    @Test fun cancelledCommandsDoNotBecomeSuccessfulResults() {
        val transport = source("AdbTransport.kt")
        assertTrue(transport.contains("catch (cancelled: CancellationException)"))
        assertTrue(transport.contains("throw cancelled"))
        assertTrue(transport.contains("AdbConsoleProtocol.wrap(validated, marker)"))
        assertTrue(transport.contains("AdbConsoleProtocol.parse(response, marker)"))
        assertTrue(source("AdbConsole.kt").contains("current?.cancel()"))
    }

    @Test fun approvedSharedLibrariesRemainPinned() {
        val build = listOf(File("app/build.gradle.kts"), File("../app/build.gradle.kts"))
            .first { it.isFile }.readText()
        assertTrue(build.contains("dev.n2k02:theme:0.1.5"))
        assertTrue(build.contains("dev.n2k02:updater:0.1.20"))
        assertTrue(build.contains("dev.n2k02:adb-core:0.1.4"))
    }
}

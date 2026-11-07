package dev.n2k02.localadb

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class AdbIdleEfficiencyTest {
    private fun source(name: String) = listOf(
        File("src/main/java/dev/n2k02/localadb/" + name),
        File("app/src/main/java/dev/n2k02/localadb/" + name)
    ).first { it.isFile }.readText()

    @Test fun onlyOneServiceOwnerAndWatchdog() {
        val engine = source("AdbEngine.kt")
        assertTrue(engine.contains("if (!active || ownedCore !== shared)"))
        assertTrue(engine.contains("shared.acquireService()"))
        assertTrue(engine.contains("AdbRecoveryWorker.schedule(app)"))
        assertFalse(engine.contains("startWatchdog("))
        assertFalse(engine.contains("startDiscovery("))
        val adapter = source("SharedAdbHostAdapter.kt")
        assertTrue(adapter.contains("AdbRecoveryWorker.schedule(app)"))
        assertTrue(adapter.contains("AdbEngine.updateHelperRecovery(app, enabled)"))
        assertTrue(engine.contains("NativeShizukuManager.maybeRecover(app)"))
        assertTrue(engine.contains("delay(120_000L)"))
        assertTrue(engine.contains("shared.state.value.connected"))
        assertTrue(adapter.contains("AdbCoreRegistry.install(it)"))
    }

    @Test fun quitReleasesWorkerObserverAndSharedCore() {
        val engine = source("AdbEngine.kt")
        assertTrue(engine.contains("observer?.cancel()"))
        assertTrue(engine.contains("scope.cancel()"))
        assertTrue(engine.contains("helperRecovery?.cancel()"))
        assertTrue(engine.contains("AdbRecoveryWorker.cancel(app)"))
        assertTrue(engine.contains("withContext(NonCancellable + Dispatchers.IO)"))
        assertTrue(engine.contains("SharedAdbHostAdapter.shutdown()"))
        assertTrue(source("SharedAdbHostAdapter.kt").contains("it.quit()"))
    }
}

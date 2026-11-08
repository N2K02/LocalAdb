package dev.n2k02.localadb

import java.io.File
import java.security.KeyPairGenerator
import org.junit.Assert.*
import org.junit.Test

class AdbSessionKeyLifecycleTest {
    private fun file(name: String): String {
        val path = "app/src/main/java/dev/n2k02/localadb/" + name
        return listOf(File(path), File("../" + path)).first { it.isFile }.readText()
    }

    @Test fun independentRsaCopiesPreserveTheStoredIdentity() {
        val original = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }
            .generateKeyPair().private
        val one = AdbSessionKey.copyOf(original)
        val two = AdbSessionKey.copyOf(original)
        assertNotSame(original, one)
        assertNotSame(original, two)
        assertNotSame(one, two)
        assertArrayEquals(original.encoded, one.encoded)
        assertArrayEquals(original.encoded, two.encoded)
    }

    @Test fun sharedCorePreservesOriginalAuthorizationAndExcludesLegacyManager() {
        val adapter = file("SharedAdbHostAdapter.kt")
        assertTrue(adapter.contains("AdbIdentity.getOrCreate(ctx)"))
        assertTrue(adapter.contains("CoreIdentity(existing.privateKey, existing.certificate)"))
        assertTrue(adapter.contains("LibAdbWire(app, identity"))
        assertTrue(adapter.contains("AdbSettings.rememberVerified(app)"))
        assertTrue(adapter.contains("AdbSettings.stopped(app)"))
        assertTrue(adapter.contains("AdbCoreRegistry.uninstall(it)"))
        val transport = file("AdbTransport.kt")
        assertFalse(transport.contains("AbsAdbConnectionManager"))
        assertFalse(transport.contains("AdbSessionKey.copyOf"))
    }

    @Test fun existingTlsCannotClaimSuccessInsteadOfRequestedTcp() {
        val transport = file("AdbTransport.kt")
        assertTrue(transport.contains("check(adb.retry())"))
        assertTrue(transport.contains("AdbTransportMode.LOCAL_TCP_5555"))
        assertTrue(transport.contains("shared().disableTcp()"))
        val engine = file("AdbEngine.kt")
        assertTrue(engine.contains("shared.retry(auto = false)"))
        assertTrue(file("SharedAdbHostAdapter.kt").contains("AdbSettings.tlsPort(app)"))
    }

    @Test fun quitIsAwaitedOffTheMainThread() {
        val engine = file("AdbEngine.kt")
        val main = listOf(File("app/src/main/java/dev/n2k02/localadb/MainActivity.kt"),
            File("../app/src/main/java/dev/n2k02/localadb/MainActivity.kt"))
            .first { it.isFile }.readText()
        assertTrue(engine.contains("suspend fun shutdown(context: Context)"))
        assertTrue(engine.contains("withContext(NonCancellable + Dispatchers.IO)"))
        assertFalse(engine.contains("runBlocking(Dispatchers.IO)"))
        assertTrue(main.indexOf("AdbEngine.shutdown(applicationContext)") <
            main.indexOf("finishAndRemoveTask()"))
    }
}

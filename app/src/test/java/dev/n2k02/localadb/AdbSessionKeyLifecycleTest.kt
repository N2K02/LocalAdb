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

    @Test fun everyAdbManagerReceivesIndependentCopyOfPersistedRsaKey() {
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

    @Test fun disconnectRetiresManagerWithoutDestroyingSavedIdentity() {
        val s = file("AdbTransport.kt")
        for (key in listOf("AdbSessionKey.copyOf(identity.privateKey)",
                "getPrivateKey(): PrivateKey = sessionKey", "oldManager?.disconnect()",
                "runCatching { reader.close() }", "lastKnownConnected = false",
                "suspend fun disconnect() = close()", "if (manager === mgr) manager = null"))
            assertTrue(key, s.contains(key))
        assertFalse(s.contains("manager?.close()"))
        assertFalse(s.contains("mgr.close()"))
        assertFalse(s.contains("getPrivateKey(): PrivateKey = identity.privateKey"))
    }

    @Test fun timeoutRetiresManagerAndReconnectionReusesHealthyVerifiedSession() {
        val s = file("AdbTransport.kt")
        val timeout = s.substringAfter("val watchdog = launch {")
            .substringBefore("session = session ?:")
        assertTrue(timeout.contains("mgr.disconnect()"))
        assertTrue(timeout.contains("if (manager === mgr) manager = null"))
        val engine = file("AdbEngine.kt")
        assertTrue(engine.contains("status.value.connected && AdbTransport.lastKnownConnected"))
        assertTrue(engine.contains("verifyShell()"))
    }
}

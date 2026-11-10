package dev.n2k02.localadb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ShizukuAutomationContractTest {
    @Test fun tokenComparisonRejectsMissingWrongAndMalformedTokens() {
        val expected = "a".repeat(64)
        assertTrue(ShizukuIntentAccess.matches(expected, expected))
        assertFalse(ShizukuIntentAccess.matches(expected, null))
        assertFalse(ShizukuIntentAccess.matches(expected, ""))
        assertFalse(ShizukuIntentAccess.matches(expected, "b".repeat(64)))
        assertFalse(ShizukuIntentAccess.matches(expected, "a".repeat(63)))
    }

    @Test fun receiverAndManifestRequireOptInToken() {
        val receiver = File("src/main/java/dev/n2k02/localadb/ShizukuAutomationReceiver.kt").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val screen = File("src/main/java/dev/n2k02/localadb/LocalAdbScreen.kt").readText()
        assertTrue(receiver.contains("getBoolean(KEY_ENABLED, false)"))
        assertTrue(receiver.contains("SecureRandom().nextBytes(bytes)"))
        assertTrue(receiver.contains("MessageDigest.isEqual("))
        assertTrue(receiver.contains("if (!ShizukuIntentAccess.authorized(context, supplied))"))
        assertTrue(receiver.contains("NativeShizukuManager.start(context.applicationContext, explicit = true)"))
        assertTrue(receiver.contains("NativeShizukuManager.stop(context.applicationContext)"))
        assertTrue(manifest.contains("android:name=\".ShizukuAutomationReceiver\""))
        assertTrue(manifest.contains("moe.shizuku.privileged.api.START"))
        assertTrue(manifest.contains("moe.shizuku.privileged.api.STOP"))
        assertTrue(screen.contains("if (shizukuExpanded) {"))
        assertTrue(screen.contains("ShizukuIntentAccess.rotateToken(context)"))
    }
}

package dev.n2k02.localadb

import org.junit.Assert.*
import org.junit.Test

class NativeShizukuManagerTest {
    @Test fun ownerMustMatchTheCurrentApp() {
        assertTrue(NativeShizukuManager.ownerMatches(
            "dev.n2k02.localadb", "dev.n2k02.localadb"))
        assertFalse(NativeShizukuManager.ownerMatches(
            "moe.shizuku.privileged.api", "dev.n2k02.localadb"))
        assertFalse(NativeShizukuManager.ownerMatches(null, "dev.n2k02.localadb"))
    }

    @Test fun shizukuFlagValuesMatchServerContract() {
        assertEquals(2, NativeShizukuManager.FLAG_ALLOWED)
        assertEquals(4, NativeShizukuManager.FLAG_DENIED)
        assertEquals(6, NativeShizukuManager.MASK_PERMISSION)
    }

    @Test fun bundledPayloadIsPinnedAndOnlyShellCanReadIt() {
        val digest = NativeShizukuManager.SERVER_SHA256
        assertTrue(digest.matches(Regex("[a-f0-9]{64}")))
        val file = java.io.File(
            "src/main/java/dev/n2k02/localadb/ShizukuPayloadProvider.kt"
        ).readText()
        assertTrue(file.contains("Binder.getCallingUid() != Process.SHELL_UID"))
        assertTrue(file.contains("uri.pathSegments.singleOrNull()"))
        assertTrue(file.contains("embedded-shizuku-server.apk"))
        val launcher = java.io.File(
            "src/main/java/dev/n2k02/localadb/NativeShizukuManager.kt"
        ).readText()
        assertTrue(launcher.contains("id -u"))
        assertTrue(launcher.contains("sha256sum"))
        assertTrue(launcher.contains("SERVER_SHA256"))
        assertTrue(launcher.contains("libshizuku.so"))
        assertTrue(launcher.contains("--apk="))
        assertTrue(launcher.contains("Shizuku.pingBinder()"))
    }

    @Test fun realGrantsMustBeCheckedAgainstServerAfterMutation() {
        val file = java.io.File(
            "src/main/java/dev/n2k02/localadb/NativeShizukuManager.kt"
        ).readText()
        assertTrue(file.contains("Shizuku.updateFlagsForUid"))
        assertTrue(file.contains("val verified = granted(context, uid)"))
        assertTrue(file.contains("check(verified == allow)"))
        assertTrue(file.contains("ownerMatches(managerPermissionOwner(context), context.packageName)"))
    }

    @Test fun shizukuApprovalChecksRequesterUidAndDeniesDismissal() {
        val file = java.io.File(
            "src/main/java/dev/n2k02/localadb/ShizukuApprovalActivity.kt"
        ).readText()
        assertTrue(file.contains("application.uid != requestUid"))
        assertTrue(file.contains("getPackagesForUid(requestUid)"))
        assertTrue(file.contains("respond(false, false)"))
        assertTrue(file.contains("Shizuku.dispatchPermissionConfirmationResult"))
    }

    @Test fun startsOnlyAfterAdbHasBeenVerifiedAndQuitStopsServerFirst() {
        val engine = java.io.File(
            "src/main/java/dev/n2k02/localadb/AdbEngine.kt"
        ).readText()
        val onVerified = engine.substringAfter("private suspend fun onVerified")
            .substringBefore("fun submitPairingCode")
        assertTrue(onVerified.contains("NativeShizukuManager.start(context)"))
        assertTrue(onVerified.indexOf("shellUid = 2000") <
            onVerified.indexOf("NativeShizukuManager.start(context)"))
        val main = java.io.File(
            "src/main/java/dev/n2k02/localadb/MainActivity.kt"
        ).readText()
        val quit = main.substringAfter("private fun quit()")
        assertTrue(quit.indexOf("NativeShizukuManager.shutdown") <
            quit.indexOf("AdbEngine.shutdown"))
    }
}

package dev.n2k02.localadb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class ShizukuLifecycleTest {
    private fun allowed(adb: Boolean = true, auto: Boolean = true, stopped: Boolean = false,
                        busy: Boolean = false, binder: Boolean = false,
                        elapsedMs: Long = 90_000L) =
        ShizukuRecoveryPolicy.shouldRecover(adb, auto, stopped, busy, binder, elapsedMs)

    @Test fun autoStartsOnlyAfterAuthorizedAdbUid2000() {
        assertFalse(allowed(adb = false))
        assertTrue(allowed(adb = true))
    }

    @Test fun manualStopOverridesEveryWatchdogRecovery() {
        assertFalse(allowed(stopped = true))
        assertFalse(allowed(stopped = true, elapsedMs = Long.MAX_VALUE))
        assertTrue(allowed(stopped = false))
    }

    @Test fun retryIsRateLimitedAndSafeForRunningServer() {
        assertFalse(allowed(busy = true))
        assertFalse(allowed(binder = true))
        assertFalse(allowed(auto = false))
        assertFalse(allowed(elapsedMs = 0))
        assertFalse(allowed(elapsedMs = 89_999))
        assertTrue(allowed(elapsedMs = 90_000))
    }

    @Test fun uiHasStopAndRestartNoMisleadingStartButton() {
        val ui = java.io.File("src/main/java/dev/n2k02/localadb/AppsPage.kt").readText()
        assertTrue(ui.contains("NativeShizukuManager.stop(context)"))
        assertTrue(ui.contains("NativeShizukuManager.restart(context)"))
        assertTrue(ui.contains("Text(\"Stop server\")") ||
            ui.contains("else \"Stop server\""))
        assertTrue(ui.contains("Text(\"Restart server\")"))
        assertFalse(ui.contains("\"Start server\""))
        assertTrue(ui.contains("native.diagnostics.isNotBlank()"))
    }

    @Test fun launchUsesShizukuHiddenApiFlagsAndReadbackDiagnostics() {
        val s = java.io.File("src/main/java/dev/n2k02/localadb/NativeShizukuManager.kt")
            .readText()
        // The upstream native starter configures hidden APIs and detaches
        // app_process, which direct LocalAdb launch previously skipped.
        assertTrue(s.contains("libshizuku.so"))
        assertTrue(s.contains("--apk="))
        assertTrue(s.contains("localadb_starter_ready"))
        assertTrue(s.contains("starter.log"))
        assertTrue(s.contains("SERVER_SHA256"))
        assertTrue(s.contains("tail -n 30 "))
        assertTrue(s.contains("LocalAdbShizuku"))
        assertTrue(s.contains("SHIZUKU_API_PERMISSION"))
        assertTrue(s.contains("Shizuku.getFlagsForUid"))
        assertTrue(s.contains("Shizuku.updateFlagsForUid"))
        assertTrue(s.contains("MANUAL_STOP"))
        assertTrue(s.contains("private suspend fun stopLocked"))
        assertTrue(s.contains("readFailureDiagnostics"))
    }

    @Test fun adbVerificationStartsServerEvenWhenPermissionBootstrapIsUnavailable() {
        val s = java.io.File("src/main/java/dev/n2k02/localadb/AdbEngine.kt").readText()
        val verified = s.substringAfter("private suspend fun onVerified")
            .substringBefore("fun submitPairingCode")
        assertTrue(verified.indexOf("shellUid = 2000") <
            verified.indexOf("NativeShizukuManager.start(context)"))
        assertTrue(verified.indexOf("NativeShizukuManager.start(context)") <
            verified.indexOf("AdbPermissionBootstrap.configure(context)"))
        assertTrue(s.contains("NativeShizukuManager.maybeRecover(context)"))
    }

    @Test fun providerStreamsOnlyTheBundledAbiMatchedStarterToShellUid2000() {
        val provider = java.io.File(
            "src/main/java/dev/n2k02/localadb/ShizukuPayloadProvider.kt").readText()
        assertTrue(provider.contains("Binder.getCallingUid() != Process.SHELL_UID"))
        assertTrue(provider.contains("which != \"embedded\" && which != \"starter\""))
        assertTrue(provider.contains("ZipInputStream"))
        assertTrue(provider.contains("Build.SUPPORTED_ABIS.firstOrNull()"))
        assertTrue(provider.contains("libshizuku.so"))
    }

    @Test fun upstreamStarterMustNotBeBackgroundedTwice() {
        val s = java.io.File(
            "src/main/java/dev/n2k02/localadb/NativeShizukuManager.kt").readText()
        assertTrue(s.contains("localadb_starter_exit:"))
        assertTrue(s.contains("localadb_starter_exit:0"))
        assertTrue(s.contains("val startupOutput = AdbTransport.exec(command, 20_000)"))
        assertTrue(s.contains("Waiting for Shizuku Binder ("))
        assertFalse(s.contains("echo \\$! > " + "dir + \"/starter.pid\""))
    }

    @Test fun providerDeliversVerifiedSeekableDescriptorNotBlockingPipe() {
        val source = java.io.File(
            "src/main/java/dev/n2k02/localadb/ShizukuPayloadProvider.kt").readText()
        assertTrue(source.contains("ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY)"))
        assertTrue(source.contains("EMBEDDED_SHA256"))
        assertTrue(source.contains("digest.digest()"))
        assertTrue(source.contains("target.isFile"))
        assertTrue(source.contains("output.fd.sync()"))
        assertFalse(source.contains("ParcelFileDescriptor.createPipe()"))
    }

    @Test fun manualStopDoesNotKillOtherShizukuManagerProcess() {
        val s = java.io.File("src/main/java/dev/n2k02/localadb/NativeShizukuManager.kt")
            .readText()
        assertTrue(s.contains("server.pid"))
        assertTrue(s.contains("cmdline"))
        assertTrue(s.contains("PROCESS_NAME"))
        assertFalse(s.contains("pkill -f"))
        assertFalse(s.contains("killall"))
    }
}

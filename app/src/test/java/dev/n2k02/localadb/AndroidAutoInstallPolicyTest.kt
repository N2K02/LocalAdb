package dev.n2k02.localadb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAutoInstallPolicyTest {
    private val policy = AndroidAutoInstallPolicy

    @Test fun acceptsOnlyPlatformInstallerInitiatorsOnModernAndroid() {
        assertTrue(policy.accepts("com.google.android.packageinstaller",
            "com.android.vending", 37))
        assertTrue(policy.accepts("com.android.packageinstaller",
            "com.android.vending", 34))
        assertTrue(policy.accepts("com.android.vending", "com.android.vending", 35))
        assertFalse(policy.accepts("com.android.shell", "com.android.vending", 37))
        assertFalse(policy.accepts("dev.n2k02.localadb", "com.android.vending", 37))
        assertFalse(policy.accepts("com.notreally.packageinstaller", "com.android.vending", 37))
        assertFalse(policy.accepts(null, "com.android.vending", 37))
        assertFalse(policy.accepts("com.google.android.packageinstaller", null, 37))
        assertFalse(policy.accepts("com.google.android.packageinstaller", "com.android.shell", 37))
    }

    @Test fun legacyAndroidChecksInstallerAttribution() {
        assertTrue(policy.accepts(null, "com.android.vending", 29))
        assertFalse(policy.accepts(null, "com.android.shell", 29))
    }

    @Test fun recognizesOnlyVerifiedOemInitiators() {
        val oem = "com.miui.packageinstaller"
        assertFalse(policy.accepts(oem, policy.VENDING, 37))
        assertTrue(policy.accepts(oem, policy.VENDING, 37, setOf(oem)))
        assertFalse(policy.accepts(oem, "dev.n2k02.localadb", 37, setOf(oem)))
        assertFalse(policy.accepts("com.android.shell", policy.VENDING, 37, setOf("com.android.shell")))
    }

    @Test fun selectedVersionRequiresNewTimestampOrSuccessfulInstallerResult() {
        assertTrue(policy.isSelectedInstall(101, 101, 11, 10, false))
        assertFalse(policy.isSelectedInstall(101, 101, 10, 10, false))
        assertTrue(policy.isSelectedInstall(101, 101, 10, 10, true))
        assertFalse(policy.isSelectedInstall(102, 101, 11, 10, true))
        assertFalse(policy.isSelectedInstall(null, 101, 11, 10, true))
    }

    @Test fun shellLaunchKeepsApkReadGrantAndSystemConfirmation() {
        val component = "com.miui.packageinstaller/.InstallActivity"
        val uri = "content://dev.n2k02.localadb.android-auto-apk/selected.apk"
        val args = policy.shellLaunchArgs(component, uri)
        assertEquals(listOf("/system/bin/am", "start", "-n", component), args.take(4))
        assertTrue(args.containsAll(listOf(policy.INSTALL_ACTION, uri, "--grant-read-uri-permission")))
        assertFalse(args.contains("install-create"))
        assertThrows(IllegalArgumentException::class.java) {
            policy.shellLaunchArgs("com.miui.packageinstaller/.InstallActivity;cmd", uri)
        }
    }

    @Test fun installerLaunchDistinguishesAcceptedRejectedAndAmbiguousResults() {
        assertEquals(AndroidAutoInstallPolicy.LaunchStatus.OPENED, policy.binderLaunchStatus(0))
        assertEquals(AndroidAutoInstallPolicy.LaunchStatus.REUSED, policy.binderLaunchStatus(3))
        assertEquals(AndroidAutoInstallPolicy.LaunchStatus.REJECTED, policy.binderLaunchStatus(1))
        assertEquals(AndroidAutoInstallPolicy.LaunchStatus.REJECTED, policy.binderLaunchStatus(100))
        assertEquals(AndroidAutoInstallPolicy.LaunchStatus.UNKNOWN, policy.binderLaunchStatus(99))
        assertEquals(AndroidAutoInstallPolicy.LaunchStatus.OPENED, policy.shellLaunchStatus(true, "Starting: Intent"))
        assertEquals(AndroidAutoInstallPolicy.LaunchStatus.REJECTED, policy.shellLaunchStatus(true,
            "Warning: Activity not started because the current activity is being kept for the user."))
        assertEquals(AndroidAutoInstallPolicy.LaunchStatus.REUSED, policy.shellLaunchStatus(true,
            "Warning: Activity not started, its current task has been brought to the front"))
        assertEquals(AndroidAutoInstallPolicy.LaunchStatus.UNKNOWN, policy.shellLaunchStatus(false, "Command timed out"))
    }

    @Test fun apkSessionUriRequiresExactAuthorityAndUniqueSessionPath() {
        val authority = "dev.n2k02.localadb.android-auto-apk"
        val session = "12345678-abcd-4321-abcd-1234567890ab"
        val prefix = "content://$authority/"
        assertEquals(session, policy.sessionFromUri("$prefix$session.apk", authority))
        assertEquals(null, policy.sessionFromUri("${prefix}selected.apk", authority))
        assertEquals(null, policy.sessionFromUri("$prefix../$session.apk", authority))
        assertEquals(null, policy.sessionFromUri("content://evil/$session.apk", authority))
        assertEquals(null, policy.sessionFromUri("$prefix$session.apk?path=other", authority))
    }

    @Test fun installerHomeCardIsLastAndExistingCardsStayCompact() {
        val main = java.io.File("src/main/java/dev/n2k02/localadb/LocalAdbScreen.kt")
            .readText()
        val home = main.substringAfter("private fun HomePage(")
            .substringBefore("private fun SettingsPage(")
        assertTrue(home.contains("ModernPanel(Modifier.fillMaxWidth(), DashboardTone.INFO, compact = true)"))
        assertTrue(home.contains("ModernPanel(Modifier.fillMaxWidth(), DashboardTone.GOOD, compact = true)"))
        assertTrue(home.contains("HomeMetric("))
        assertTrue(home.indexOf("AndroidAutoInstallerCard()") > home.lastIndexOf("Android permissions"))
        assertTrue(home.contains("NativeShizukuManager.stop(context)"))
        assertTrue(home.contains("NativeShizukuManager.restart(context)"))
    }

    @Test fun providerUsesOnlyIsolatedReadOnlyUriAndHelperChecksUid() {
        val provider = java.io.File("src/main/java/dev/n2k02/localadb/AndroidAutoApkProvider.kt")
            .readText()
        val service = java.io.File("src/main/java/dev/n2k02/localadb/LocalHelperService.kt")
            .readText()
        val installer = java.io.File("src/main/java/dev/n2k02/localadb/AndroidAutoPackageInstaller.kt")
            .readText()
        assertTrue(provider.contains("AndroidAutoInstallPolicy.sessionFromUri(uri.toString()"))
        assertTrue(provider.contains("mode != \"r\""))
        assertTrue(service.contains("Process.myUid() == Process.SHELL_UID"))
        assertTrue(service.contains("AndroidAutoInstallPolicy.sessionFromUri(uri,"))
        assertTrue(installer.contains("DirectInstallAttribution.readBack(app, session.packageName)"))
        assertTrue(installer.contains("previousUpdate"))
        assertTrue(installer.contains("compatibleSigner"))
        assertTrue(installer.contains("Shizuku.bindUserService(args, conn)"))
        assertTrue(installer.contains("Shizuku.unbindUserService(args, conn, true)"))
    }

    @Test fun aaInstallerKeepsSystemAndDirectOptionsWithoutComparisonUi() {
        val card = java.io.File("src/main/java/dev/n2k02/localadb/AndroidAutoInstallerCard.kt").readText()
        assertTrue(card.contains("AndroidAutoPackageInstaller::installViaShizuku)"))
        assertTrue(card.contains("AndroidAutoPackageInstaller::installDirectFromHome)"))
        assertTrue(card.contains("installApkWithSystem()"))
        assertFalse(card.contains("Compare AABrowser / AAYouTube"))
        assertFalse(card.contains("showComparison"))
        assertFalse(card.contains("AndroidAutoInstallDiagnostics.summary(context)"))
    }

    @Test fun resultCallbackAndBoundedReadbackNeverLaunchAgain() {
        val installer = java.io.File("src/main/java/dev/n2k02/localadb/AndroidAutoPackageInstaller.kt").readText()
        val activity = java.io.File("src/main/java/dev/n2k02/localadb/MainActivity.kt").readText()
        val verification = installer.substringAfter("fun onInstallerResult(").substringBefore("fun cancelSelection(")
        assertTrue(activity.contains("ActivityResultContracts.StartActivityForResult()"))
        assertTrue(activity.contains("AndroidAutoPackageInstaller.onInstallerResult(this, result.resultCode)"))
        assertTrue(verification.contains("attempt !== session"))
        assertTrue(verification.contains("index == delays.lastIndex"))
        assertTrue(verification.contains("Activity.RESULT_CANCELED"))
        assertTrue(verification.contains("if (resultCode == null && !expired)"))
        assertTrue(verification.contains("Still awaiting Android installer confirmation."))
        assertTrue(installer.contains("withTimeoutOrNull(15_000) { attached.await() }"))
        assertFalse(verification.contains("launch(activity,"))
        assertFalse(installer.contains("shell_works"))
    }

    @Test fun shellIndependentlyChecksThatSelectedComponentIsASystemHandler() {
        val helper = java.io.File("src/main/java/dev/n2k02/localadb/LocalHelperService.kt").readText()
        val installer = java.io.File("src/main/java/dev/n2k02/localadb/AndroidAutoPackageInstaller.kt").readText()
        assertTrue(helper.contains("PackageManager.MATCH_SYSTEM_ONLY"))
        assertTrue(helper.contains("ComponentName.unflattenFromString(it.trim()) == component"))
        assertTrue(helper.contains("startActivityAsUser"))
        // A Binder call may already have opened the installer even when its
        // result is rejected or ambiguous. Never enqueue a second shell launch.
        assertTrue(helper.contains("binderLaunchAttempted = true"))
        assertTrue(helper.contains("if (binderLaunchAttempted || binderResult.status != LaunchStatus.REJECTED) return binderResult"))

        assertTrue(helper.contains("ProcessBuilder(args)"))
        assertTrue(helper.contains("process.waitFor(12, java.util.concurrent.TimeUnit.SECONDS)"))
        assertTrue(helper.contains("kotlin.concurrent.thread(name = \"installer-command-output\""))
        assertTrue(helper.contains("process.inputStream.bufferedReader().use"))
        assertTrue(helper.indexOf("process.inputStream.bufferedReader().use") <
            helper.indexOf("process.waitFor(12, java.util.concurrent.TimeUnit.SECONDS)"))
        assertTrue(helper.contains("4096 - output.length"))
        assertTrue(helper.contains("process.destroyForcibly()"))
        assertFalse(helper.contains("pm install-create"))
        assertTrue(installer.contains("val handlers = findSystemInstallers(activity, intent)"))
        assertTrue(installer.contains("openInstaller)(intent)"))
        assertTrue(installer.contains("if (result.status != LaunchStatus.REJECTED) return result.copy(detail = detail)"))
        assertTrue(installer.contains("if (path == AndroidAutoInstallPolicy.Path.SHELL) return result.copy(detail = detail)"))
    }

    @Test fun quitClearsInstallerSessionBeforeStoppingShizuku() {
        val activity = java.io.File("src/main/java/dev/n2k02/localadb/MainActivity.kt")
            .readText()
        val quit = activity.substringAfter("private fun quit()")
        assertTrue(quit.indexOf("AndroidAutoPackageInstaller.shutdown(") <
            quit.indexOf("NativeShizukuManager.shutdown("))
    }
}

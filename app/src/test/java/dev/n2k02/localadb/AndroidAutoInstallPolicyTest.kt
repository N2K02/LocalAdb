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

    @Test fun launchFallsBackOnlyWhenShellUnavailableOrPreviouslyRejected() {
        assertEquals(AndroidAutoInstallPolicy.Path.SHELL, policy.firstPath(true, true))
        assertEquals(AndroidAutoInstallPolicy.Path.APP, policy.firstPath(false, true))
        assertEquals(AndroidAutoInstallPolicy.Path.APP, policy.firstPath(true, false))
    }

    @Test fun noRetryAfterCancelOrRepeatedFailureOrVerifiedSuccess() {
        assertFalse(policy.shouldRetry(changed = false, accepted = false, alreadyRetried = false))
        assertFalse(policy.shouldRetry(changed = true, accepted = true, alreadyRetried = false))
        assertFalse(policy.shouldRetry(changed = true, accepted = false, alreadyRetried = true))
        assertTrue(policy.shouldRetry(changed = true, accepted = false, alreadyRetried = false))
    }

    @Test fun shellCommandLaunchesSystemInstallerAndNeverPmInstall() {
        val command = policy.shellCommand("dev.n2k02.localadb", policy.GOOGLE_INSTALLER)
        assertTrue(command.startsWith("am start -n " + policy.GOOGLE_INSTALLER))
        assertTrue(command.contains("content://dev.n2k02.localadb.android-auto-apk/selected.apk"))
        assertTrue(command.contains("--es android.intent.extra.INSTALLER_PACKAGE_NAME com.android.vending"))
        assertTrue(command.contains("--ez android.intent.extra.NOT_UNKNOWN_SOURCE true"))
        assertFalse(command.contains("pm install"))
        assertFalse(command.contains("adb install"))
    }

    @Test fun shellCommandRejectsArbitraryComponentsAndInjection() {
        assertThrows(IllegalArgumentException::class.java) {
            policy.shellCommand("dev.n2k02.localadb;rm -rf /",
                policy.GOOGLE_INSTALLER)
        }
        assertThrows(IllegalArgumentException::class.java) {
            policy.shellCommand("dev.n2k02.localadb", "evil/.Activity")
        }
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

    @Test fun providerUsesOnlyFixedReadOnlyUriAndHelperChecksUid() {
        val provider = java.io.File("src/main/java/dev/n2k02/localadb/AndroidAutoApkProvider.kt")
            .readText()
        val service = java.io.File("src/main/java/dev/n2k02/localadb/LocalHelperService.kt")
            .readText()
        val installer = java.io.File("src/main/java/dev/n2k02/localadb/AndroidAutoPackageInstaller.kt")
            .readText()
        assertTrue(provider.contains("uri.path == \"/selected.apk\""))
        assertTrue(provider.contains("mode != \"r\""))
        assertTrue(service.contains("Process.myUid() == Process.SHELL_UID"))
        assertTrue(service.contains("check(uri == expected)"))
        assertTrue(installer.contains("getInstallSourceInfo(name)"))
        assertTrue(installer.contains("previousUpdate"))
        assertTrue(installer.contains("compatibleSigner"))
        assertTrue(installer.contains("Shizuku.bindUserService(args, conn)"))
        assertTrue(installer.contains("Shizuku.unbindUserService(args, conn, true)"))
    }

    @Test fun quitClearsInstallerSessionBeforeStoppingShizuku() {
        val activity = java.io.File("src/main/java/dev/n2k02/localadb/MainActivity.kt")
            .readText()
        val quit = activity.substringAfter("private fun quit()")
        assertTrue(quit.indexOf("AndroidAutoPackageInstaller.shutdown(") <
            quit.indexOf("NativeShizukuManager.shutdown("))
    }
}

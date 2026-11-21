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
        assertTrue(service.contains("check(uri == expected && request.type"))
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
        assertFalse(verification.contains("launch(activity,"))
        assertFalse(installer.contains("shell_works"))
    }

    @Test fun shellIndependentlyChecksThatSelectedComponentIsASystemHandler() {
        val helper = java.io.File("src/main/java/dev/n2k02/localadb/LocalHelperService.kt").readText()
        val installer = java.io.File("src/main/java/dev/n2k02/localadb/AndroidAutoPackageInstaller.kt").readText()
        assertTrue(helper.contains("PackageManager.MATCH_SYSTEM_ONLY"))
        assertTrue(helper.contains("ComponentName.unflattenFromString(it.trim()) == component"))
        assertTrue(helper.contains("startActivityAsUser"))
        assertTrue(helper.contains("ProcessBuilder(args)"))
        assertTrue(helper.contains("process.waitFor(12, java.util.concurrent.TimeUnit.SECONDS)"))
        assertFalse(helper.contains("pm install-create"))
        assertTrue(installer.contains("val handlers = findSystemInstallers(activity, intent)"))
        assertTrue(installer.contains("openInstaller)(intent)"))
        assertTrue(installer.contains("check(result == \"ok\")"))
    }

    @Test fun quitClearsInstallerSessionBeforeStoppingShizuku() {
        val activity = java.io.File("src/main/java/dev/n2k02/localadb/MainActivity.kt")
            .readText()
        val quit = activity.substringAfter("private fun quit()")
        assertTrue(quit.indexOf("AndroidAutoPackageInstaller.shutdown(") <
            quit.indexOf("NativeShizukuManager.shutdown("))
    }
}

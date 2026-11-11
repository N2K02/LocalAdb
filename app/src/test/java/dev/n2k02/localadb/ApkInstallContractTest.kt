package dev.n2k02.localadb

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import java.util.UUID

class ApkInstallContractTest {
    @Test fun exportedHandlerFiltersOnlyApkMimeAndContentUris() {
        val xml = File("src/main/AndroidManifest.xml").readText()
        assertTrue(xml.contains("android:name=\".ApkInstallActivity\""))
        assertTrue(xml.contains("application/vnd.android.package-archive"))
        assertTrue(xml.contains("android.intent.action.VIEW"))
        assertTrue(xml.contains("android.intent.action.SEND"))
        assertTrue(xml.contains("android:scheme=\"content\""))
        assertTrue(xml.contains("android:exported=\"false\""))
    }

    @Test fun eachApkSessionUsesUniqueConstrainedUri() {
        val provider = File("src/main/java/dev/n2k02/localadb/ApkInstallProvider.kt").readText()
        val vm = File("src/main/java/dev/n2k02/localadb/ApkInstallViewModel.kt").readText()
        assertTrue(provider.contains("validSession(session)"))
        assertTrue(provider.contains("MODE_READ_ONLY"))
        assertTrue(provider.contains("mode != \"r\""))
        assertTrue(provider.contains("uri.pathSegments.size != 1"))
        assertTrue(vm.contains("UUID.randomUUID().toString()"))
        assertTrue(vm.contains("require(!output.exists())"))
        assertTrue(vm.contains("ApkInstallPolicy.MAX_APK_BYTES"))
    }

    @Test fun directInstallUsesGeneratedAidlAndShellSessions() {
        val activity = File("src/main/java/dev/n2k02/localadb/ApkInstallActivity.kt").readText()
        val model = File("src/main/java/dev/n2k02/localadb/ApkInstallViewModel.kt").readText()
        val helper = File("src/main/java/dev/n2k02/localadb/DirectApkInstallService.kt").readText()
        val caller = File("src/main/java/dev/n2k02/localadb/AndroidAutoPackageInstaller.kt").readText()
        val aidl = File("src/main/aidl/dev/n2k02/localadb/IDirectApkInstallService.aidl").readText()
        assertTrue(activity.contains("model::installWithAdb"))
        assertTrue(activity.contains("Native install"))
        assertTrue(model.contains("Shizuku.getUid() == Process.SHELL_UID"))
        assertTrue(caller.contains("IDirectApkInstallService.Stub.asInterface"))
        assertTrue(caller.contains("installer.installApk(fd, file.length(), requestedInstaller.orEmpty())"))
        assertTrue(caller.contains("installer.serviceUid == Process.SHELL_UID"))
        assertTrue(helper.contains("IDirectApkInstallService.Stub()"))
        assertTrue(helper.contains("Process.myUid() == Process.SHELL_UID"))
        assertTrue(helper.contains("DirectInstallAttribution.sessionCreateArgs("))
        val policy = File("src/main/java/dev/n2k02/localadb/DirectInstallAttribution.kt").readText()
        assertTrue(policy.contains("install-create"))
        assertTrue(helper.contains("install-write"))
        assertTrue(helper.contains("install-commit"))
        assertTrue(helper.contains("install-abandon"))
        assertTrue(aidl.contains("ParcelFileDescriptor"))
        assertFalse(caller.substringAfter("internal suspend fun installDirect(")
            .substringBefore("internal suspend fun startWithLocalHelper(").contains("transact("))
        val oldHelper = File("src/main/java/dev/n2k02/localadb/LocalHelperService.kt").readText()
        assertFalse(oldHelper.contains("INSTALL_APK_DIRECT"))
    }

    @Test fun directInstallReusesExistingShizukuInstallerSafetyChecks() {
        val existing = File("src/main/java/dev/n2k02/localadb/AndroidAutoPackageInstaller.kt").readText()
        val viewModel = File("src/main/java/dev/n2k02/localadb/ApkInstallViewModel.kt").readText()
        assertTrue(existing.contains("fun validateInstallCompatibility("))
        assertTrue(existing.contains("compatibleSigner(parsed, old)"))
        assertTrue(existing.contains("check(newCode >= oldCode)"))
        assertTrue(viewModel.contains("AndroidAutoPackageInstaller.validateInstallCompatibility(app, staged)"))
        assertTrue(viewModel.indexOf("validateInstallCompatibility(app, staged)") <
            viewModel.indexOf("AndroidAutoPackageInstaller.installDirect(app, staged)"))
    }

    @Test fun optionalPlayRecordModeIsSharedAndAlwaysReadBack() {
        val direct = File("src/main/java/dev/n2k02/localadb/DirectApkInstallService.kt").readText()
        val caller = File("src/main/java/dev/n2k02/localadb/AndroidAutoPackageInstaller.kt").readText()
        val model = File("src/main/java/dev/n2k02/localadb/ApkInstallViewModel.kt").readText()
        val ui = File("src/main/java/dev/n2k02/localadb/ApkInstallActivity.kt").readText()
        val aidl = File("src/main/aidl/dev/n2k02/localadb/IDirectApkInstallService.aidl").readText()
        assertTrue(direct.contains("DirectInstallAttribution.sessionCreateArgs("))
        assertTrue(caller.contains("DirectInstallAttribution.requestedInstaller(context)"))
        assertTrue(caller.contains("DirectInstallAttribution.readBack(app, selected.packageName)"))
        assertTrue(caller.contains("attributionStatus = verdict"))
        assertTrue(model.contains("DirectInstallAttribution.readBack(app, state.value.packageName)"))
        assertTrue(ui.contains("Google Play as installer of record"))
        assertTrue(ui.contains("DirectInstallAttribution.setEnabled("))
        assertTrue(ui.contains("state.sourceDetails"))
        assertTrue(aidl.contains("String installerPackageName"))
        assertTrue(ui.contains("Native install"))
        assertTrue(caller.contains("internal suspend fun startWithLocalHelper("))
    }

    @Test fun composeDialogAndRealActivityResultAreUsed() {
        val code = File("src/main/java/dev/n2k02/localadb/ApkInstallActivity.kt").readText()
        assertTrue(code.contains("N2K02Theme"))
        assertTrue(code.contains("Dialog("))
        assertTrue(code.contains("usePlatformDefaultWidth = false"))
        assertTrue(code.contains("Modifier.weight(1.38f)"))
        assertTrue(code.contains("MaterialTheme.shapes.extraLarge"))
        assertTrue(code.contains("ActivityResultContracts.StartActivityForResult()"))
        assertTrue(code.contains("model.complete("))
        assertTrue(code.contains("model::installWithAdb"))
        assertTrue(code.contains("MaterialTheme.colorScheme.errorContainer"))
        assertTrue(code.contains("state.isError"))
        assertTrue(code.contains("APK size:"))
        assertFalse(code.contains("android.app.AlertDialog"))
    }

    @Test fun lifecycleStateRetainedUntilInstallerResult() {
        val code = File("src/main/java/dev/n2k02/localadb/ApkInstallViewModel.kt").readText()
        assertTrue(code.contains("AndroidViewModel"))
        assertTrue(code.contains("if (started) return"))
        assertTrue(code.contains("Phase.INSTALLING"))
        assertTrue(code.contains("Phase.RESULT"))
        assertTrue(code.contains("override fun onCleared()"))
    }

    @Test fun installedVersionIsVerifiedBeforeReportingAdbSuccess() {
        val code = File("src/main/java/dev/n2k02/localadb/ApkInstallViewModel.kt").readText()
        assertTrue(code.contains("fun installed(): Boolean"))
        assertTrue(code.contains("ApkInstallPolicy.installedChanged"))
        assertTrue(code.contains("app.revokeUriPermission(stagedUri()"))
    }
}

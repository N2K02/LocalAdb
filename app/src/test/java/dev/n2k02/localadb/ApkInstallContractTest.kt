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

    @Test fun directInstallUsesVerifiedShellAndOnlyStreamsOneApk() {
        val activity = File("src/main/java/dev/n2k02/localadb/ApkInstallActivity.kt").readText()
        val model = File("src/main/java/dev/n2k02/localadb/ApkInstallViewModel.kt").readText()
        val helper = File("src/main/java/dev/n2k02/localadb/DirectApkInstallService.kt").readText()
        val installer = File("src/main/java/dev/n2k02/localadb/AndroidAutoPackageInstaller.kt").readText()
        assertTrue(activity.contains("model::installWithAdb"))
        assertTrue(activity.contains("Native install"))
        assertTrue(model.contains("Shizuku.getUid() == Process.SHELL_UID"))
        assertTrue(model.contains("AndroidAutoPackageInstaller.installDirect"))
        assertTrue(helper.contains("Process.myUid() == Process.SHELL_UID"))
        assertTrue(helper.contains("const val COMMAND = IBinder.FIRST_CALL_TRANSACTION"))
        assertTrue(helper.contains("OP_INSTALL"))
        assertTrue(helper.contains("ParcelFileDescriptor.CREATOR.createFromParcel(data)"))
        assertTrue(helper.contains("Os.fstat(apk.fileDescriptor)"))
        assertTrue(helper.contains("\"-S\", bytes.toString(), \"-\""))
        assertTrue(installer.contains("ParcelFileDescriptor.MODE_READ_ONLY"))
        assertTrue(installer.contains("DirectApkInstallService.OP_PING"))
        assertTrue(installer.contains("DirectApkInstallService.OP_INSTALL"))
        assertTrue(installer.contains("DirectApkInstallService.PROTOCOL"))
        assertTrue(installer.contains("DirectApkInstallService::class.java"))
        assertTrue(installer.contains(".version(2)"))
        val oldHelper = File("src/main/java/dev/n2k02/localadb/LocalHelperService.kt").readText()
        assertFalse(oldHelper.contains("INSTALL_APK_DIRECT"))
        assertFalse(activity.contains("ADB-assisted"))
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

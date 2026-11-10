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

    @Test fun adbAssistedPathCannotLaunchArbitraryUriOrComponent() {
        val activity = File("src/main/java/dev/n2k02/localadb/ApkInstallActivity.kt").readText()
        val helper = File("src/main/java/dev/n2k02/localadb/LocalHelperService.kt").readText()
        assertTrue(activity.contains("Shizuku.getUid() == Process.SHELL_UID"))
        assertTrue(activity.contains("startWithLocalHelper"))
        assertTrue(helper.contains("Process.myUid() == Process.SHELL_UID"))
        assertTrue(helper.contains("ApkInstallProvider.validSession(genericId)"))
        assertTrue(helper.contains("intent.action == Intent.ACTION_INSTALL_PACKAGE"))
        assertTrue(helper.contains("Unsupported installer"))
    }

    @Test fun composeDialogAndRealActivityResultAreUsed() {
        val code = File("src/main/java/dev/n2k02/localadb/ApkInstallActivity.kt").readText()
        assertTrue(code.contains("N2K02Theme"))
        assertTrue(code.contains("AlertDialog("))
        assertTrue(code.contains("MaterialTheme.shapes.extraLarge"))
        assertTrue(code.contains("ActivityResultContracts.StartActivityForResult()"))
        assertTrue(code.contains("model.complete("))
        assertTrue(code.contains("model.markAwaitingVerification()"))
        assertFalse(code.contains("android.app.AlertDialog"))
    }

    @Test fun lifecycleStateRetainedUntilInstallerResult() {
        val code = File("src/main/java/dev/n2k02/localadb/ApkInstallViewModel.kt").readText()
        assertTrue(code.contains("AndroidViewModel"))
        assertTrue(code.contains("if (started) return"))
        assertTrue(code.contains("Phase.INSTALLING"))
        assertTrue(code.contains("Phase.VERIFYING"))
        assertTrue(code.contains("override fun onCleared()"))
    }

    @Test fun installedVersionIsVerifiedBeforeReportingAdbSuccess() {
        val code = File("src/main/java/dev/n2k02/localadb/ApkInstallViewModel.kt").readText()
        assertTrue(code.contains("fun installed(): Boolean"))
        assertTrue(code.contains("ApkInstallPolicy.installedChanged"))
        assertTrue(code.contains("app.revokeUriPermission(stagedUri()"))
    }
}

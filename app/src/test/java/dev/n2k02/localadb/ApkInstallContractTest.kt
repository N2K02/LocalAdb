package dev.n2k02.localadb

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ApkInstallContractTest {
    @Test fun externalEntryPointsRequireAnApkMimeType() {
        val xml = File("src/main/AndroidManifest.xml").readText()
        assertTrue(xml.contains("android:name=\".ApkInstallActivity\""))
        assertTrue(xml.contains("application/vnd.android.package-archive"))
        assertTrue(xml.contains("android.intent.action.VIEW"))
        assertTrue(xml.contains("android.intent.action.SEND"))
    }
    @Test fun installerDoesNotAcceptFileUrisOrLaunchWithoutConfirmation() {
        val source = File("src/main/java/dev/n2k02/localadb/ApkInstallActivity.kt").readText()
        assertTrue(source.contains("uri.scheme != \"content\""))
        assertTrue(source.contains("setPositiveButton(\"Install\")"))
        assertTrue(source.contains("Intent.FLAG_GRANT_READ_URI_PERMISSION"))
        assertTrue(source.contains("canRequestPackageInstalls()"))
    }
    @Test fun adbAssistedPathMustKeepNativeInstallerAndShellIdentityCheck() {
        val activity = File("src/main/java/dev/n2k02/localadb/ApkInstallActivity.kt").readText()
        val helper = File("src/main/java/dev/n2k02/localadb/LocalHelperService.kt").readText()
        assertTrue(activity.contains("openAdbInstaller()"))
        assertTrue(activity.contains("Shizuku.getUid() == Process.SHELL_UID"))
        assertTrue(activity.contains("AndroidAutoPackageInstaller.startWithLocalHelper"))
        assertTrue(helper.contains("uri == expected || uri == generic"))
        assertTrue(helper.contains("Process.myUid() == Process.SHELL_UID"))
        assertTrue(helper.contains("intent.action == Intent.ACTION_INSTALL_PACKAGE"))
    }
    @Test fun standardInstallerRequestsAndRecordsResult() {
        val code = File("src/main/java/dev/n2k02/localadb/ApkInstallActivity.kt").readText()
        assertTrue(code.contains("Intent.EXTRA_RETURN_RESULT"))
        assertTrue(code.contains("startActivityForResult"))
        assertTrue(code.contains("override fun onActivityResult"))
        assertTrue(code.contains("Installation completed"))
    }
    @Test fun providerIsReadOnlyAndConstrainedToOneApk() {
        val source = File("src/main/java/dev/n2k02/localadb/ApkInstallProvider.kt").readText()
        assertTrue(source.contains("uri.path != \"/selected.apk\""))
        assertTrue(source.contains("mode != \"r\""))
        assertTrue(source.contains("MODE_READ_ONLY"))
    }
}

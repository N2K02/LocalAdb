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
    @Test fun providerIsReadOnlyAndConstrainedToOneApk() {
        val source = File("src/main/java/dev/n2k02/localadb/ApkInstallProvider.kt").readText()
        assertTrue(source.contains("uri.path != \"/selected.apk\""))
        assertTrue(source.contains("mode != \"r\""))
        assertTrue(source.contains("MODE_READ_ONLY"))
    }
}

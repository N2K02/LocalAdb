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

    @Test fun openWithApkUsesTransparentDialogHostInsteadOfMainAppBackground() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val styles = File("src/main/res/values/styles.xml").readText()
        val handler = manifest.substringAfter("<activity android:name=\".ApkInstallActivity\"")
            .substringBefore("</activity>")
        assertTrue(handler.contains("android:theme=\"@style/Theme.LocalAdb.ApkInstaller\""))
        assertTrue(styles.contains("name=\"Theme.LocalAdb.ApkInstaller\""))
        assertTrue(styles.contains("<item name=\"android:windowIsTranslucent\">true</item>"))
        assertTrue(styles.contains("<item name=\"android:windowBackground\">@android:color/transparent</item>"))
        assertTrue(styles.contains("<item name=\"android:backgroundDimEnabled\">false</item>"))
        val activity = File("src/main/java/dev/n2k02/localadb/ApkInstallActivity.kt").readText()
        assertTrue(activity.contains("window?.setDimAmount(0f)"))
        assertTrue(activity.contains("window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)"))
        assertFalse(handler.contains("android.intent.category.LAUNCHER"))
    }

    @Test fun externalInstallerIsTaskIsolatedAndClosingNeverOpensHome() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val installer = manifest.substringAfter("<activity android:name=\".ApkInstallActivity\"")
            .substringBefore("</activity>")
        assertTrue(installer.contains("android:taskAffinity=\"\""))
        assertTrue(installer.contains("android:documentLaunchMode=\"always\""))
        assertTrue(installer.contains("android:excludeFromRecents=\"true\""))
        val activity = File("src/main/java/dev/n2k02/localadb/ApkInstallActivity.kt").readText()
        assertTrue(activity.contains("val close = { closeInstaller() }"))
        assertTrue(activity.contains("if (isTaskRoot) finishAndRemoveTask() else finish()"))
        assertTrue(activity.contains("model.abandon()"))
        assertFalse(activity.contains("if (model.state.value.phase == ApkInstallUiState.Phase.INSTALLING) return"))
        assertFalse(activity.contains("MainActivity::class.java"))
        assertFalse(activity.contains("getLaunchIntentForPackage"))
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
        assertTrue(activity.contains("::beginAdbInstall"))
        assertTrue(activity.contains("Native install"))
        assertTrue(model.contains("Shizuku.getUid() == Process.SHELL_UID"))
        assertTrue(caller.contains("IDirectApkInstallService.Stub.asInterface"))
        assertTrue(caller.contains("installer.installApk(fd, file.length(), requestedInstaller.orEmpty(), callback)"))
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
        assertTrue(File("src/main/java/dev/n2k02/localadb/ApkInstallForegroundService.kt").readText().contains("AndroidAutoPackageInstaller.validateInstallCompatibility(applicationContext, file)"))
        val service = File("src/main/java/dev/n2k02/localadb/ApkInstallForegroundService.kt").readText()
        assertTrue(service.indexOf("validateInstallCompatibility(applicationContext, file)") <
            service.indexOf("AndroidAutoPackageInstaller.installDirect(applicationContext, file)"))
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
        assertTrue(File("src/main/java/dev/n2k02/localadb/ApkInstallForegroundService.kt").readText().contains("DirectInstallAttribution.readBack(applicationContext, pkg)"))
        assertTrue(ui.contains("Google Play as installer of record"))
        assertTrue(ui.contains("DirectInstallAttribution.setEnabled("))
        assertTrue(ui.contains("Live activity"))
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
        assertTrue(code.contains("::beginAdbInstall"))
        assertTrue(code.contains("MaterialTheme.colorScheme.errorContainer"))
        assertTrue(code.contains("state.isError"))
        assertTrue(code.contains("APK size:"))
        assertFalse(code.contains("android.app.AlertDialog"))
    }

    @Test fun installerShowsRealStagesInsteadOfResultCard() {
        val activity = File("src/main/java/dev/n2k02/localadb/ApkInstallActivity.kt").readText()
        val model = File("src/main/java/dev/n2k02/localadb/ApkInstallViewModel.kt").readText()
        val service = File("src/main/java/dev/n2k02/localadb/ApkInstallForegroundService.kt").readText()
        assertTrue(activity.contains("Live activity"))
        assertTrue(activity.contains("state.activitySteps.forEach"))
        assertFalse(activity.contains("InstallerOutcomeBadge("))
        assertFalse(activity.contains("state.sourceDetails.isNotBlank()"))
        assertTrue(activity.contains("model.installing(native = true)"))
        assertTrue(model.contains("Native package installation verified"))
        assertTrue(model.contains("Opening Android package installer"))
        assertTrue(activity.contains("if (closing.value) return"))
        assertTrue(activity.contains("Closing…"))
        assertTrue(activity.contains("withContext(Dispatchers.IO) { model.abandon() }"))
        assertTrue(model.contains("fun appendInstallStage("))
        assertTrue(model.contains("fun directInstallResult("))
        assertTrue(service.contains("AndroidAutoPackageInstaller.installDirect(applicationContext, file) { step ->"))
        assertTrue(service.contains("Verifying installed package version"))
        val helper = File("src/main/java/dev/n2k02/localadb/DirectApkInstallService.kt").readText()
        val caller = File("src/main/java/dev/n2k02/localadb/AndroidAutoPackageInstaller.kt").readText()
        val aidl = File("src/main/aidl/dev/n2k02/localadb/IDirectApkInstallService.aidl").readText()
        assertTrue(aidl.contains("IInstallProgressCallback callback"))
        assertTrue(caller.contains("IInstallProgressCallback.Stub()"))
        assertTrue(helper.contains("pm install-create: starting"))
        assertTrue(helper.contains("pm install-write: completed"))
        assertTrue(helper.contains("pm install-commit: completed"))
        assertTrue(helper.contains("pm install-write: $percent% transferred"))
        assertTrue(activity.contains("state.message.isNotBlank()"))
    }

    @Test fun blockedNotificationStateIsExplainedAndLogged() {
        val activity = File("src/main/java/dev/n2k02/localadb/ApkInstallActivity.kt").readText()
        val service = File("src/main/java/dev/n2k02/localadb/ApkInstallForegroundService.kt").readText()
        assertTrue(activity.contains("notificationProblem()"))
        assertTrue(activity.contains("Settings.ACTION_APP_NOTIFICATION_SETTINGS"))
        assertTrue(activity.contains("Notification settings"))
        assertTrue(activity.contains("POST_NOTIFICATIONS"))
        assertTrue(service.contains("fun notificationAvailability()"))
        assertTrue(service.contains("areNotificationsEnabled()"))
        assertTrue(service.contains("IMPORTANCE_NONE"))
        assertTrue(service.contains("APK notification: foreground start"))
        assertTrue(service.contains("APK notification: completion"))
    }

    @Test fun foregroundInstallNotificationsAndBackgroundDismissal() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val service = File("src/main/java/dev/n2k02/localadb/ApkInstallForegroundService.kt").readText()
        val activity = File("src/main/java/dev/n2k02/localadb/ApkInstallActivity.kt").readText()
        val vm = File("src/main/java/dev/n2k02/localadb/ApkInstallViewModel.kt").readText()
        assertTrue(manifest.contains("android.permission.POST_NOTIFICATIONS"))
        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE_DATA_SYNC"))
        assertTrue(manifest.contains("android:foregroundServiceType=\"dataSync\""))
        assertTrue(service.contains("startForeground(ACTIVE_ID"))
        assertTrue(service.contains("setProgress(if (ongoing) 0 else 0, 0, ongoing)"))
        assertTrue(service.contains("APK installed"))
        assertTrue(service.contains("APK installation failed"))
        assertTrue(service.contains("ApkInstallPolicy.installedChanged"))
        assertTrue(service.contains("stopForeground(STOP_FOREGROUND_REMOVE)"))
        assertTrue(activity.contains("Close · continue in background"))
        assertTrue(activity.contains("ActivityResultContracts.RequestPermission()"))
        assertTrue(activity.contains("installResultReceiver"))
        assertTrue(vm.contains("app.startForegroundService(request)"))
        assertTrue(vm.contains("fun directInstallResult("))
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

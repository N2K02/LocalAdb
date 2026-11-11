package dev.n2k02.localadb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CompactHomeInstallerUiTest {
    private fun source(name: String) =
        File("src/main/java/dev/n2k02/localadb/$name").readText()

    @Test fun allFourHomeMetricsRemainVisibleWithSmallerTwoLineTiles() {
        val main = source("LocalAdbScreen.kt")
        val home = main.substringAfter("private fun HomePage(")
            .substringBefore("private fun SettingsPage(")
        val ui = source("ModernDashboardUi.kt")
        assertTrue(home.contains("HomeMetric(\"Shell\""))
        assertTrue(home.contains("HomeMetric(\"Pairing\""))
        assertTrue(home.contains("HomeMetric(\"Transport\""))
        assertTrue(home.contains("HomeMetric(\"Discovery\""))
        assertTrue(home.contains("HomeMetric(\"Clients\""))
        assertTrue(home.contains("HomeMetric(\"Grants\""))
        assertTrue(ui.contains("Two-line metrics preserve the 1×4 Home summary"))
        assertTrue(ui.contains("horizontalArrangement = Arrangement.spacedBy(3.dp)"))
        assertTrue(ui.contains("vertical = if (compact) 4.dp else 6.dp"))
        assertFalse(home.contains("if (connection.connected) {\n                    Text(connection.message"))
    }

    @Test fun shizukuControlsShareOneCompactRowAndKeepDiagnostics() {
        val home = source("LocalAdbScreen.kt")
            .substringAfter("private fun HomePage(")
            .substringBefore("private fun SettingsPage(")
        assertTrue(home.contains("Three compact controls share one row"))
        assertTrue(home.contains("NativeShizukuManager.stop(context)"))
        assertTrue(home.contains("NativeShizukuManager.restart(context)"))
        assertTrue(home.contains("LocalHelperDiagnostics.start(context)"))
        assertTrue(home.contains("showDiagnostics"))
        assertTrue(home.contains("if (helperTest.running ||"))
        assertTrue(home.contains("if (coverage != null && coverage < 1f)"))
        assertTrue(home.contains("DashboardPresentation.permissionName(permission)"))
    }

    @Test fun installerDetailsUseReadbackFieldsInsteadOfParsingDebugText() {
        val ui = source("AndroidAutoInstallerCard.kt")
        val source = source("AndroidAutoPackageInstaller.kt")
        assertTrue(ui.contains("rememberSaveable { mutableStateOf(false) }"))
        assertTrue(ui.contains("InstallerDetailRow(\"Package\""))
        assertTrue(ui.contains("InstallerDetailRow(\"Version\""))
        assertTrue(ui.contains("InstallerDetailRow(\"Initiator\""))
        assertTrue(ui.contains("InstallerDetailRow(\"Installer\""))
        assertTrue(ui.contains("state.installingPackage"))
        assertTrue(ui.contains("state.initiatingPackage"))
        assertTrue(ui.contains("if (showDetails) AndroidAutoInstallerDetails(state)"))
        assertTrue(ui.contains("Google Play attribution does not guarantee"))
        assertTrue(source.contains("initiatingPackage = initiator, installingPackage = installing"))
        assertTrue(source.contains("initiatingPackage = null, installingPackage = null"))
        assertFalse(ui.contains("Text(state.details, fontFamily = FontFamily.Monospace"))
        assertTrue(ui.contains("SelectionContainer"))
    }

    @Test fun shizukuHomeInstallerDefaultsToDirectAndOffersNativeFallback() {
        val ui = source("AndroidAutoInstallerCard.kt")
        val installer = source("AndroidAutoPackageInstaller.kt")
        assertTrue(ui.contains("AndroidAutoPackageInstaller::installDirectFromHome"))
        assertTrue(ui.contains("AndroidAutoPackageInstaller::install"))
        assertTrue(ui.contains("Text(\"Native install\""))
        assertTrue(ui.contains("Native install opens Android confirmation."))
        assertTrue(ui.contains("Google Play as installer of record"))
        assertTrue(ui.contains("DirectInstallAttribution.setEnabled(context, it)"))
        assertTrue(installer.contains("requestedInstaller = requestedInstaller"))
        assertTrue(installer.contains("initiatingPackage = source.initiating"))
        assertTrue(installer.contains("installingPackage = source.installing"))
        assertTrue(ui.contains("Direct ADB installs do not claim Google Play attribution."))
        assertTrue(installer.contains("fun installDirectFromHome(activity: Activity)"))
        assertTrue(installer.contains("validateInstallCompatibility(app, file)"))
        assertTrue(installer.contains("installDirect(app, file)"))
        assertTrue(installer.contains("state.value.copy("))
        assertTrue(installer.contains("Package manager reported success"))
    }

    @Test fun resultBadgeIsSeparateFromAdbReadinessAndNativeActionIsProminent() {
        val ui = source("AndroidAutoInstallerCard.kt")
        val controller = source("AndroidAutoPackageInstaller.kt")
        assertTrue(ui.contains("InstallerResultSummary(state)"))
        assertTrue(ui.contains("ResultBadgeStyle(\"Installation failed\""))
        assertTrue(ui.contains("ResultBadgeStyle(\"Cancelled or rejected\""))
        assertTrue(ui.contains("ResultBadgeStyle(\"Installed\""))
        assertTrue(ui.contains("server.serviceReady -> \"ADB ready\""))
        assertTrue(ui.contains("FilledTonalButton("))
        assertTrue(ui.contains("onClick = { activity?.let(AndroidAutoPackageInstaller::install) }"))
        assertTrue(ui.contains("Text(\"Native install · Android confirmation\""))
        assertFalse(ui.contains("TextButton(\n                enabled = activity != null"))
        assertTrue(controller.contains("enum class ResultStatus"))
        assertTrue(controller.contains("resultStatus = ResultStatus.CANCELLED"))
        assertTrue(controller.contains("resultStatus = ResultStatus.FAILED"))
        assertTrue(controller.contains("resultStatus = ResultStatus.INSTALLED"))
    }

    @Test fun selectedApkHasCancelActionThatDiscardsStateAndFileOnlyWhenIdle() {
        val ui = source("AndroidAutoInstallerCard.kt")
        val installer = source("AndroidAutoPackageInstaller.kt")
        assertTrue(ui.contains("AndroidAutoPackageInstaller.cancelSelection(context)"))
        assertTrue(ui.contains("Text(\"Cancel\", maxLines = 1)"))
        assertTrue(ui.contains("showDetails = false"))
        assertTrue(ui.contains("Text(\"Native install\", maxLines = 1)"))
        assertTrue(installer.contains("fun cancelSelection(context: Context)"))
        assertTrue(installer.contains("if (state.value.busy || state.value.pending) return"))
        assertTrue(installer.contains("shutdown(context)"))
        assertTrue(installer.contains("archive = null"))
        assertTrue(installer.contains("AndroidAutoApkProvider.file(context).delete()"))
        assertTrue(installer.contains("context.revokeUriPermission("))
    }

    @Test fun installerRemainsLastCardAndBottomNavigationIsUnchanged() {
        val main = source("LocalAdbScreen.kt")
        val home = main.substringAfter("private fun HomePage(")
            .substringBefore("private fun SettingsPage(")
        assertTrue(home.indexOf("AndroidAutoInstallerCard()") > home.indexOf("Android permissions"))
        assertTrue(main.contains("Page.HOME -> HomePage(pageModifier)"))
        assertTrue(main.contains("NavigationBarItem("))
    }
}
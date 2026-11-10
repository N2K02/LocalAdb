package dev.n2k02.localadb

import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import dev.n2k02.theme.N2K02Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/** User-initiated APK entry point. Uses the same theme and Material 3 as LocalADB. */
class ApkInstallActivity : ComponentActivity() {
    private lateinit var model: ApkInstallViewModel
    private var awaitingAdbPrompt = false

    private val standardInstaller = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        model.complete(result.resultCode == RESULT_OK, result.resultCode == RESULT_CANCELED)
    }

    private val unknownSources = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (packageManager.canRequestPackageInstalls()) openStandardInstaller()
        else model.resetReady("Unknown-app installation permission was not granted")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[ApkInstallViewModel::class.java]
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            }
            else -> null
        }
        model.start(uri)
        setContent {
            N2K02Theme {
                val state by model.state.collectAsState()
                InstallerDialog(state)
            }
        }
    }

    @Composable
    private fun InstallerDialog(state: ApkInstallUiState) {
        val working = state.phase == ApkInstallUiState.Phase.STAGING ||
            state.phase == ApkInstallUiState.Phase.INSTALLING
        AlertDialog(
            onDismissRequest = {
                if (!working) { model.abandon(); finish() }
            },
            icon = { Icon(Icons.Rounded.Android, contentDescription = null) },
            title = {
                Text(when (state.phase) {
                    ApkInstallUiState.Phase.STAGING -> "Reading APK"
                    ApkInstallUiState.Phase.READY -> "Install ${state.name}?"
                    ApkInstallUiState.Phase.PERMISSION -> "Allow APK installation"
                    ApkInstallUiState.Phase.INSTALLING -> "Opening installer"
                    ApkInstallUiState.Phase.VERIFYING -> "Verify installation"
                    ApkInstallUiState.Phase.RESULT -> "Installation result"
                    ApkInstallUiState.Phase.ERROR -> "Unable to open APK"
                })
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (working) CircularProgressIndicator()
                    if (state.packageName.isNotBlank()) {
                        Text(state.packageName, style = MaterialTheme.typography.bodyMedium)
                        Text("Version: ${state.version.ifBlank { "Unknown" }}",
                            style = MaterialTheme.typography.bodyMedium)
                        Text("Size: ${state.size / 1024} KB",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.message.isNotBlank()) {
                        Text(state.message, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (state.phase == ApkInstallUiState.Phase.PERMISSION) {
                        Text("Android requires permission to install apps from this source.")
                    }
                }
            },
            confirmButton = {
                when (state.phase) {
                    ApkInstallUiState.Phase.READY ->
                        TextButton(onClick = ::openStandardInstaller) { Text("Install") }
                    ApkInstallUiState.Phase.PERMISSION ->
                        TextButton(onClick = ::requestUnknownSources) { Text("Settings") }
                    ApkInstallUiState.Phase.VERIFYING ->
                        TextButton(onClick = ::verifyAdbInstall) { Text("Verify") }
                    ApkInstallUiState.Phase.RESULT, ApkInstallUiState.Phase.ERROR ->
                        TextButton(onClick = { model.abandon(); finish() }) { Text("Close") }
                    else -> {}
                }
            },
            dismissButton = {
                when (state.phase) {
                    ApkInstallUiState.Phase.READY -> Row {
                        TextButton(onClick = { model.abandon(); finish() }) { Text("Cancel") }
                        TextButton(onClick = ::openAdbInstaller) { Text("ADB-assisted") }
                    }
                    ApkInstallUiState.Phase.PERMISSION ->
                        TextButton(onClick = { model.resetReady("Use ADB-assisted installation or grant permission") }) {
                            Text("Back")
                        }
                    ApkInstallUiState.Phase.VERIFYING ->
                        TextButton(onClick = { model.abandon(); finish() }) { Text("Close") }
                    else -> {}
                }
            },
            shape = MaterialTheme.shapes.extraLarge,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    }

    private fun requestUnknownSources() {
        try {
            unknownSources.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName")))
        } catch (failure: Exception) {
            reportFailure("Cannot open installation permissions: ${failure.message}")
        }
    }

    private fun installerIntent(): Intent {
        val uri = model.stagedUri()
        return Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            clipData = ClipData.newUri(contentResolver, "APK", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }
    }

    private fun openStandardInstaller() {
        if (model.state.value.phase !in listOf(
                ApkInstallUiState.Phase.READY, ApkInstallUiState.Phase.PERMISSION)) return
        if (!packageManager.canRequestPackageInstalls()) {
            model.permissionRequired()
            return
        }
        try {
            val intent = installerIntent()
            if (intent.resolveActivity(packageManager) == null)
                error("No Android package installer is available")
            model.installing()
            standardInstaller.launch(intent)
            appLog.append("APK installer: native confirmation launched")
        } catch (failure: Exception) {
            reportFailure("System installer unavailable: ${failure.message}")
        }
    }

    private fun openAdbInstaller() {
        if (model.state.value.phase != ApkInstallUiState.Phase.READY) return
        val ready = NativeShizukuManager.state.value.serviceReady && runCatching {
            Shizuku.pingBinder() && Shizuku.getUid() == Process.SHELL_UID
        }.getOrDefault(false)
        if (!ready) {
            model.resetReady("ADB-assisted installer requires an active UID 2000 Shizuku connection.")
            return
        }
        val candidates = listOf(AndroidAutoInstallPolicy.GOOGLE_INSTALLER,
            AndroidAutoInstallPolicy.AOSP_INSTALLER)
        val selected = candidates.firstOrNull { flat ->
            ComponentName.unflattenFromString(flat)?.let { component ->
                runCatching { packageManager.getActivityInfo(component, 0) }.isSuccess
            } == true
        }
        if (selected == null) {
            model.resetReady("No supported privileged installer found; use the standard installer.")
            return
        }
        val request = installerIntent().apply {
            component = ComponentName.unflattenFromString(selected)
        }
        runCatching {
            grantUriPermission(request.component!!.packageName, model.stagedUri(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.onFailure {
            model.resetReady("Could not grant APK access. Try the standard installer.")
            return
        }
        model.installing()
        lifecycleScope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    AndroidAutoPackageInstaller.startWithLocalHelper(
                        this@ApkInstallActivity, request)
                }
                check(outcome == "ok") { outcome }
                awaitingAdbPrompt = true
                appLog.append("APK installer: ADB-assisted native prompt launched")
                model.markAwaitingVerification()
            } catch (failure: Exception) {
                reportFailure("ADB-assisted launch failed: ${failure.message}. Use Install instead.")
            }
        }
    }

    private fun verifyAdbInstall() {
        if (!awaitingAdbPrompt) return
        if (model.installed()) model.complete(resultSuccess = true, cancelled = false)
        else {
            model.markAwaitingVerification()
            appLog.append("APK installer: no installation change detected")
        }
    }

    private fun reportFailure(message: String) {
        Log.w("LocalAdbApkInstall", message)
        appLog.append("APK installer: ${message.take(180)}")
        model.resetReady(message)
    }
}

package dev.n2k02.localadb

import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import dev.n2k02.theme.N2K02Theme

/** User-initiated APK entry point. Uses the same theme and Material 3 as LocalADB. */
class ApkInstallActivity : ComponentActivity() {
    private lateinit var model: ApkInstallViewModel

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
        val ready = state.phase == ApkInstallUiState.Phase.READY
        val close = { model.abandon(); finish() }
        Dialog(
            onDismissRequest = { if (!working) close() },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = !working,
                dismissOnClickOutside = !working
            )
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.96f)
                    .widthIn(max = 560.dp)
                    .wrapContentHeight(),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.extraLarge
                    ) {
                        Icon(
                            Icons.Rounded.InstallMobile,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(12.dp).size(28.dp)
                        )
                    }
                    Text(
                        when (state.phase) {
                            ApkInstallUiState.Phase.STAGING -> "Preparing APK"
                            ApkInstallUiState.Phase.READY -> "Ready to install"
                            ApkInstallUiState.Phase.PERMISSION -> "Allow installation"
                            ApkInstallUiState.Phase.INSTALLING -> "Installing APK"
                            ApkInstallUiState.Phase.RESULT -> "Installation result"
                            ApkInstallUiState.Phase.ERROR -> "Couldn't open APK"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                    if (working) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Text("Processing APK", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (state.packageName.isNotBlank()) {
                        ElevatedCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.large
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(state.name.ifBlank { state.packageName },
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold)
                                Text(state.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(state.version.ifBlank { "Unknown version" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    softWrap = true)
                                Text("APK size: ${"%.1f".format(state.size / 1048576.0)} MB",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (ready && !state.isError) {
                        Text(
                            "Install directly with ADB, or use Native install for Android confirmation.",
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (state.phase == ApkInstallUiState.Phase.PERMISSION) {
                        Text("Allow LocalADB to install apps from this source in Android settings.",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    if (state.message.isNotBlank()) {
                        if (state.isError || state.phase == ApkInstallUiState.Phase.ERROR) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.large,
                                color = MaterialTheme.colorScheme.errorContainer
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(Icons.Rounded.ErrorOutline,
                                        contentDescription = "Installation error",
                                        tint = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.size(20.dp))
                                    Text(state.message,
                                        modifier = Modifier.weight(1f),
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        } else {
                            Text(state.message,
                                modifier = Modifier.fillMaxWidth(),
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    when (state.phase) {
                        ApkInstallUiState.Phase.READY -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(
                                    onClick = close,
                                    modifier = Modifier.weight(0.78f),
                                    contentPadding = PaddingValues(horizontal = 2.dp)
                                ) { Text("Cancel", maxLines = 1, style = MaterialTheme.typography.labelMedium) }
                                FilledTonalButton(
                                    onClick = ::openStandardInstaller,
                                    modifier = Modifier.weight(1.38f),
                                    shape = MaterialTheme.shapes.extraLarge,
                                    contentPadding = PaddingValues(horizontal = 2.dp)
                                ) { Text("Native install", maxLines = 1,
                                    style = MaterialTheme.typography.labelMedium) }
                                Button(
                                    onClick = model::installWithAdb,
                                    modifier = Modifier.weight(0.98f),
                                    shape = MaterialTheme.shapes.extraLarge,
                                    contentPadding = PaddingValues(horizontal = 2.dp)
                                ) { Text("Install", maxLines = 1,
                                    style = MaterialTheme.typography.labelMedium) }
                            }
                        }
                        ApkInstallUiState.Phase.PERMISSION -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = {
                                    model.resetReady("Connect ADB or use Native install.")
                                }) { Text("Back") }
                                Button(onClick = ::requestUnknownSources) { Text("Settings") }
                            }
                        }
                        ApkInstallUiState.Phase.RESULT, ApkInstallUiState.Phase.ERROR ->
                            Button(onClick = close) { Text("Close") }
                        else -> Unit
                    }
                }
            }
        }
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

    private fun reportFailure(message: String) {
        Log.w("LocalAdbApkInstall", message)
        appLog.append("APK installer: ${message.take(180)}")
        model.resetReady(message, error = true)
    }
}

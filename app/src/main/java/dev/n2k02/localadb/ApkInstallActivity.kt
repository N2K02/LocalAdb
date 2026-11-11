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
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
        AlertDialog(
            onDismissRequest = {
                if (!working) { model.abandon(); finish() }
            },
            icon = {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.extraLarge
                ) {
                    Icon(
                        Icons.Rounded.InstallMobile,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(16.dp).size(32.dp)
                    )
                }
            },
            title = {
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
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (working) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp),
                                strokeWidth = 3.dp)
                            Text("Please keep LocalADB open",
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (state.packageName.isNotBlank()) {
                        ElevatedCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.large
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    state.name.ifBlank { state.packageName },
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    state.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(
                                        "Version ${state.version.ifBlank { "Unknown" }}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        "${state.size / 1024} KB",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    if (ready) {
                        Text(
                            "Install directly using the connected ADB shell, or choose Native install for Android’s confirmation screen.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    if (state.phase == ApkInstallUiState.Phase.PERMISSION) {
                        Text(
                            "Allow LocalADB to install apps from this source in Android settings.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    if (state.message.isNotBlank()) {
                        Text(
                            state.message,
                            color = if (state.phase == ApkInstallUiState.Phase.ERROR)
                                MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            },
            confirmButton = {
                when (state.phase) {
                    ApkInstallUiState.Phase.READY ->
                        Button(
                            onClick = model::installWithAdb,
                            shape = MaterialTheme.shapes.extraLarge
                        ) {
                            Icon(Icons.Rounded.InstallMobile, contentDescription = null,
                                modifier = Modifier.size(18.dp))
                            Text("Install", modifier = Modifier.padding(start = 8.dp))
                        }
                    ApkInstallUiState.Phase.PERMISSION ->
                        Button(onClick = ::requestUnknownSources,
                            shape = MaterialTheme.shapes.extraLarge) { Text("Open settings") }
                    ApkInstallUiState.Phase.RESULT, ApkInstallUiState.Phase.ERROR ->
                        Button(onClick = { model.abandon(); finish() },
                            shape = MaterialTheme.shapes.extraLarge) { Text("Close") }
                    else -> {}
                }
            },
            dismissButton = {
                when (state.phase) {
                    ApkInstallUiState.Phase.READY ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = { model.abandon(); finish() }) {
                                Text("Cancel")
                            }
                            FilledTonalButton(
                                onClick = ::openStandardInstaller,
                                shape = MaterialTheme.shapes.extraLarge
                            ) { Text("Native install") }
                        }
                    ApkInstallUiState.Phase.PERMISSION ->
                        TextButton(onClick = {
                            model.resetReady("Connect ADB or choose Native install.")
                        }) { Text("Back") }
                    else -> {}
                }
            },
            shape = MaterialTheme.shapes.extraLarge,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
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

    private fun reportFailure(message: String) {
        Log.w("LocalAdbApkInstall", message)
        appLog.append("APK installer: ${message.take(180)}")
        model.resetReady(message)
    }
}

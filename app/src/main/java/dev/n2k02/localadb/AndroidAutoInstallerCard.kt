package dev.n2k02.localadb

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.InfoOutline
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun AndroidAutoInstallerCard() {
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycle = LocalLifecycleOwner.current
    val state by AndroidAutoPackageInstaller.state.collectAsState()
    val server by NativeShizukuManager.state.collectAsState()
    var showDetails by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            showDetails = false
            AndroidAutoPackageInstaller.select(context, uri)
        }
    }

    DisposableEffect(lifecycle, activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) AndroidAutoPackageInstaller.onPause()
            if (event == Lifecycle.Event.ON_RESUME && activity != null)
                AndroidAutoPackageInstaller.onResume(activity)
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }

    ModernPanel(Modifier.fillMaxWidth(), DashboardTone.NEUTRAL, compact = true) {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            ModernSectionHeader(
                title = "Shizuku package installer",
                subtitle = "Install APKs with Play attribution",
                icon = Icons.Rounded.Android,
                tone = DashboardTone.NEUTRAL,
                status = when {
                    state.pending || state.busy -> "Working"
                    state.success -> "Verified"
                    server.serviceReady -> "Ready"
                    else -> "Shizuku offline"
                },
                statusTone = when {
                    state.success -> DashboardTone.GOOD
                    state.pending || state.busy -> DashboardTone.WARN
                    server.serviceReady -> DashboardTone.GOOD
                    else -> DashboardTone.WARN
                },
                compact = true
            )
            if (state.packageName != null) {
                Text(
                    state.packageName + (if (state.version.isNotBlank()) " · v" + state.version else ""),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Text(state.message, style = MaterialTheme.typography.bodySmall,
                color = if (state.success) DashboardTone.GOOD.accent
                    else MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = !state.busy && !state.pending,
                    onClick = {
                        picker.launch(arrayOf("application/vnd.android.package-archive",
                            "application/octet-stream"))
                    }
                ) {
                    Icon(Icons.Rounded.FileOpen, contentDescription = null,
                        modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Choose APK", maxLines = 1)
                }
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = activity != null && state.packageName != null &&
                        !state.busy && !state.pending,
                    onClick = { activity?.let(AndroidAutoPackageInstaller::install) }
                ) {
                    Icon(Icons.Rounded.InstallMobile, contentDescription = null,
                        modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Install", maxLines = 1)
                }
            }
            val hasDetails = state.packageName != null || state.installMethod != null ||
                state.initiatingPackage != null || state.installingPackage != null ||
                state.details.isNotBlank()
            if (hasDetails) {
                Surface(
                    modifier = Modifier.fillMaxWidth()
                        .clickable { showDetails = !showDetails },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Installer details",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelLarge)
                        Icon(
                            if (showDetails) Icons.Rounded.KeyboardArrowUp
                            else Icons.Rounded.KeyboardArrowDown,
                            contentDescription = if (showDetails) "Hide installer details"
                                else "Show installer details",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                if (showDetails) AndroidAutoInstallerDetails(state)
            }
            if (!server.serviceReady) {
                Text("Start the embedded Shizuku manager for the shell-assisted method.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AndroidAutoInstallerDetails(state: AndroidAutoPackageInstaller.State) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.packageName?.let { InstallerDetailRow("Package", it) }
            if (state.version.isNotBlank()) InstallerDetailRow("Version", "v" + state.version)
            state.installMethod?.let {
                InstallerDetailRow("Method",
                    if (it == "SHELL") "Shizuku · shell" else "System installer")
            }
            if (state.initiatingPackage != null || state.installingPackage != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                InstallerDetailRow("Initiator", state.initiatingPackage ?: "Not recorded")
                InstallerDetailRow("Installer", state.installingPackage ?: "Not recorded")
            }
            if (state.details.isNotBlank()) {
                SelectionContainer {
                    Text(state.details, style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(Icons.Rounded.InfoOutline, contentDescription = null,
                    modifier = Modifier.size(17.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "Google Play attribution does not guarantee this app will appear in Android Auto.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun InstallerDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, modifier = Modifier.width(68.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer(modifier = Modifier.weight(1f)) {
            Text(value,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

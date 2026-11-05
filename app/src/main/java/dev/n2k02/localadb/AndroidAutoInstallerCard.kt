package dev.n2k02.localadb

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    var showDetails by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) AndroidAutoPackageInstaller.select(context, uri)
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
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ModernSectionHeader(
                title = "Shizuku package installer",
                subtitle = "Install Android Auto APKs with Google Play attribution",
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
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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
                    androidx.compose.material3.Icon(Icons.Rounded.FileOpen,
                        contentDescription = null)
                    Spacer(Modifier.width(5.dp))
                    Text("Choose APK", maxLines = 1)
                }
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = activity != null && state.packageName != null &&
                        !state.busy && !state.pending,
                    onClick = { activity?.let(AndroidAutoPackageInstaller::install) }
                ) {
                    androidx.compose.material3.Icon(Icons.Rounded.InstallMobile,
                        contentDescription = null)
                    Spacer(Modifier.width(5.dp))
                    Text("Install", maxLines = 1)
                }
            }
            if (state.details.isNotBlank()) {
                TextButton(onClick = { showDetails = !showDetails }) {
                    Text(if (showDetails) "Hide installer details" else "Show installer details")
                }
                if (showDetails) {
                    Text(state.details, fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            if (!server.serviceReady) {
                Text("Start the embedded Shizuku manager for the shell-assisted method.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

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
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Close
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
    var playRecord by rememberSaveable { mutableStateOf(DirectInstallAttribution.enabled(context)) }
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
                subtitle = "Direct ADB install · native fallback",
                icon = Icons.Rounded.Android,
                tone = DashboardTone.NEUTRAL,
                status = when {
                    state.pending || state.busy -> "Working"
                    server.serviceReady -> "ADB ready"
                    else -> "Shizuku offline"
                },
                statusTone = when {
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
            InstallerResultSummary(state)

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
                    onClick = { activity?.let(AndroidAutoPackageInstaller::installDirectFromHome) }
                ) {
                    Icon(Icons.Rounded.InstallMobile, contentDescription = null,
                        modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Install", maxLines = 1)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Google Play as installer of record",
                        style = MaterialTheme.typography.labelMedium)
                    Text("Optional · not genuine Play initiation",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = playRecord,
                    enabled = !state.busy && !state.pending,
                    onCheckedChange = {
                        playRecord = it
                        DirectInstallAttribution.setEnabled(context, it)
                    }
                )
            }
            if (state.packageName != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        modifier = Modifier.weight(0.85f),
                        enabled = !state.busy && !state.pending,
                        onClick = {
                            AndroidAutoPackageInstaller.cancelSelection(context)
                            showDetails = false
                        }
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = null,
                            modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Cancel", maxLines = 1)
                    }
                    FilledTonalButton(
                        modifier = Modifier.weight(1.15f),
                        enabled = activity != null && !state.busy && !state.pending,
                        shape = MaterialTheme.shapes.extraLarge,
                        onClick = { activity?.let(AndroidAutoPackageInstaller::install) }
                    ) {
                        Icon(Icons.Rounded.PhoneAndroid, contentDescription = null,
                            modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Native install", maxLines = 1)
                    }
                }
                Text("Native install opens Android confirmation.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
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

/**
 * Shows one distinct result chip, separate from Shizuku's connectivity badge.
 * The description stays readable but no longer looks like an unstructured log.
 */
@Composable
private fun InstallerResultSummary(state: AndroidAutoPackageInstaller.State) {
    val result = state.resultStatus
    if (result == AndroidAutoPackageInstaller.ResultStatus.IDLE) {
        Text(
            state.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    val (label, icon, background, foreground) = when (result) {
        AndroidAutoPackageInstaller.ResultStatus.INSTALLING ->
            ResultBadgeStyle("Installing", Icons.Rounded.HourglassTop,
                MaterialTheme.colorScheme.tertiaryContainer,
                MaterialTheme.colorScheme.onTertiaryContainer)
        AndroidAutoPackageInstaller.ResultStatus.INSTALLED ->
            ResultBadgeStyle("Installed", Icons.Rounded.CheckCircle,
                MaterialTheme.colorScheme.primaryContainer,
                MaterialTheme.colorScheme.onPrimaryContainer)
        AndroidAutoPackageInstaller.ResultStatus.FAILED ->
            ResultBadgeStyle("Installation failed", Icons.Rounded.ErrorOutline,
                MaterialTheme.colorScheme.errorContainer,
                MaterialTheme.colorScheme.onErrorContainer)
        AndroidAutoPackageInstaller.ResultStatus.CANCELLED ->
            ResultBadgeStyle("Cancelled or rejected", Icons.Rounded.Info,
                MaterialTheme.colorScheme.surfaceContainerHighest,
                MaterialTheme.colorScheme.onSurface)
        AndroidAutoPackageInstaller.ResultStatus.NOT_VERIFIED ->
            ResultBadgeStyle("Needs verification", Icons.Rounded.Info,
                MaterialTheme.colorScheme.tertiaryContainer,
                MaterialTheme.colorScheme.onTertiaryContainer)
        AndroidAutoPackageInstaller.ResultStatus.IDLE -> error("Handled above")
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(color = background, shape = MaterialTheme.shapes.extraLarge) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Icon(icon, contentDescription = null, tint = foreground,
                    modifier = Modifier.size(17.dp))
                Text(label, style = MaterialTheme.typography.labelMedium,
                    color = foreground)
            }
        }
        Text(state.message, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private data class ResultBadgeStyle(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val background: androidx.compose.ui.graphics.Color,
    val foreground: androidx.compose.ui.graphics.Color,
)

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
                    when (it) {
                        "DIRECT_ADB" -> "ADB shell · direct"
                        "SHELL" -> "Shizuku · system installer"
                        else -> "System installer"
                    })
            }
            if (state.attributionStatus.isNotBlank()) {
                Text(state.attributionStatus, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            // Neutral note is visually separate from the read-back metadata.
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Row(
                    modifier = Modifier.padding(9.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(Icons.Rounded.Info, contentDescription = null,
                        modifier = Modifier.size(17.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        if (state.installMethod == "DIRECT_ADB")
                            "Direct ADB installs do not claim Google Play attribution. The chosen installer of record may differ from the actual initiator."
                        else "Google Play attribution does not guarantee this app will appear in Android Auto.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
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

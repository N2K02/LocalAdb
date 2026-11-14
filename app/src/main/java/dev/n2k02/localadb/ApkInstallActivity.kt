package dev.n2k02.localadb

import android.content.ClipData
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.app.NotificationManager
import android.Manifest
import android.annotation.SuppressLint
import android.os.Build
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import android.view.WindowManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
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
    private val closing = mutableStateOf(false)

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { model.installWithAdb() }

    private val installResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getStringExtra(ApkInstallForegroundService.EXTRA_SESSION) != model.session) return
            if (intent.action == ApkInstallForegroundService.ACTION_STAGE) {
                model.appendInstallStage(intent.getStringExtra(ApkInstallForegroundService.EXTRA_STAGE).orEmpty())
                return
            }
            model.directInstallResult(
                intent.getBooleanExtra(ApkInstallForegroundService.EXTRA_SUCCESS, false),
                intent.getStringExtra(ApkInstallForegroundService.EXTRA_MESSAGE).orEmpty()
            )
        }
    }

    // Android 12 and below use the legacy overload; Android 13+ specifies NOT_EXPORTED.
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(ApkInstallForegroundService.ACTION_RESULT)
            addAction(ApkInstallForegroundService.ACTION_STAGE)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(installResultReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(installResultReceiver, filter)
        }
    }

    override fun onStop() {
        unregisterReceiver(installResultReceiver)
        super.onStop()
    }

    private fun notificationProblem(): String? {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return "Android notification permission is off. Installation can continue, but alerts may be hidden."
        val manager = getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return "LocalADB notifications are disabled in Android settings."
        if (manager.getNotificationChannel("apk_install_status")?.importance ==
            NotificationManager.IMPORTANCE_NONE
        ) return "The APK installations notification channel is disabled."
        return null
    }

    private fun openAppNotificationSettings() {
        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }

    private fun beginAdbInstall() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            model.installWithAdb()
        }
    }

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
        val working = state.phase == ApkInstallUiState.Phase.STAGING
        val ready = state.phase == ApkInstallUiState.Phase.READY
        var playRecord by rememberSaveable {
            mutableStateOf(DirectInstallAttribution.enabled(this@ApkInstallActivity))
        }
        val close = { closeInstaller() }
        var showMore by rememberSaveable(state.phase.name) { mutableStateOf(false) }
        // Remove both the dialog dim layer and any dim inherited from its window.
        Dialog(
            onDismissRequest = {
                if (showMore) {
                    showMore = false
                    appLog.append("APK popup: Back collapsed details")
                } else if (!working && !closing.value) {
                    appLog.append("APK popup: Back dismissed installer")
                    close()
                }
            },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false
            )
        ) {
            val window = (LocalView.current.parent as? DialogWindowProvider)?.window
            SideEffect {
                window?.setDimAmount(0f)
                window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.96f)
                    .widthIn(max = 540.dp)
                    .wrapContentHeight(),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp)
                        .heightIn(max = 680.dp).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.large) {
                            Icon(Icons.Rounded.InstallMobile, contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(10.dp).size(24.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("LocalADB", style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold)
                            Text(when (state.phase) {
                                ApkInstallUiState.Phase.STAGING -> "Preparing APK"
                                ApkInstallUiState.Phase.READY -> "Ready to install"
                                ApkInstallUiState.Phase.PERMISSION -> "Allow installation"
                                ApkInstallUiState.Phase.INSTALLING -> "Installing package"
                                ApkInstallUiState.Phase.RESULT -> if (state.outcome == ApkInstallUiState.Outcome.SUCCESS)
                                    "Installation complete" else "Installation finished"
                                ApkInstallUiState.Phase.ERROR -> "Unable to open APK"
                            }, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    if (state.phase == ApkInstallUiState.Phase.RESULT) {
                        Surface(modifier = Modifier.fillMaxWidth(),
                            color = if (state.outcome == ApkInstallUiState.Outcome.SUCCESS)
                                MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.extraLarge) {
                            Row(modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(if (state.outcome == ApkInstallUiState.Outcome.SUCCESS)
                                        Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                                    contentDescription = null, modifier = Modifier.size(32.dp),
                                    tint = if (state.outcome == ApkInstallUiState.Outcome.SUCCESS)
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onErrorContainer)
                                Column {
                                    Text(if (state.outcome == ApkInstallUiState.Outcome.SUCCESS)
                                            "Successfully installed" else "Installation finished",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold)
                                    Text(state.name.ifBlank { state.packageName },
                                        style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }

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
                    if (ready) {
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
                            Switch(checked = playRecord, onCheckedChange = {
                                playRecord = it
                                DirectInstallAttribution.setEnabled(this@ApkInstallActivity, it)
                            })
                        }
                    }
                    if (state.phase == ApkInstallUiState.Phase.INSTALLING) {
                        notificationProblem()?.let { problem ->
                            Text(problem, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = ::openAppNotificationSettings) {
                                Text("Notification settings")
                            }
                        }
                    }
                    if (state.phase == ApkInstallUiState.Phase.PERMISSION) {
                        Text("Allow LocalADB to install apps from this source in Android settings.",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    if (state.phase == ApkInstallUiState.Phase.INSTALLING ||
                        (state.phase == ApkInstallUiState.Phase.RESULT && showMore)) {
                        ElevatedCard(modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.extraLarge) {
                            Column(modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Live activity", style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold)
                                    val status = when {
                                        state.phase == ApkInstallUiState.Phase.INSTALLING -> "RUNNING"
                                        state.outcome == ApkInstallUiState.Outcome.SUCCESS -> "COMPLETE"
                                        else -> "FINISHED"
                                    }
                                    Text(status, style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary)
                                }
                                state.activitySteps.forEach { step ->
                                    val detail = step.substringAfter("  ", step)
                                    val timestamp = step.substringBefore("  ", "")
                                    val succeeded = detail.endsWith("completed") ||
                                        detail.contains("verified", ignoreCase = true) ||
                                        detail.contains("returned: Success")
                                    val failed = detail.contains("failed", ignoreCase = true) ||
                                        detail.contains("cancelled", ignoreCase = true)
                                    Row(modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.Top,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Icon(
                                            when {
                                                failed -> Icons.Rounded.ErrorOutline
                                                succeeded -> Icons.Rounded.CheckCircle
                                                detail.contains("starting") || detail.contains("transferring") ->
                                                    Icons.Rounded.HourglassTop
                                                else -> Icons.Rounded.Terminal
                                            }, contentDescription = null,
                                            tint = when {
                                                failed -> MaterialTheme.colorScheme.error
                                                succeeded -> MaterialTheme.colorScheme.primary
                                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                                            }, modifier = Modifier.size(18.dp)
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(detail, style = MaterialTheme.typography.bodySmall)
                                            if (timestamp.isNotBlank()) {
                                                Text(timestamp, style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }
                                }
                                val transfer = state.activitySteps.lastOrNull {
                                    it.contains("pm install-write:") && it.contains("% transferred")
                                }
                                val transferPercent = transfer?.let {
                                    Regex("""(\d+)% transferred""").find(it)?.groupValues?.get(1)?.toIntOrNull()
                                }
                                val finalizing = state.activitySteps.lastOrNull()?.let {
                                    it.contains("pm install-commit") ||
                                        it.contains("Verifying installed package") ||
                                        it.contains("ADB package manager returned")
                                } == true
                                if (state.phase == ApkInstallUiState.Phase.INSTALLING) {
                                    if (transferPercent != null && !finalizing) {
                                        LinearProgressIndicator(
                                            progress = { transferPercent.coerceIn(0, 100) / 100f },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Text("APK transfer $transferPercent% · package commit is separate",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    } else {
                                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                        Text(if (finalizing) "Finalizing package installation"
                                            else "Waiting for installation stage",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                    if (state.phase == ApkInstallUiState.Phase.RESULT) {
                        TextButton(
                            onClick = {
                                showMore = !showMore
                                appLog.append("APK popup: details " +
                                    if (showMore) "expanded" else "collapsed")
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(if (showMore) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(if (showMore) "Show less" else "Show more")
                        }
                    }
                    if (state.message.isNotBlank() &&
                        (state.phase == ApkInstallUiState.Phase.ERROR ||
                         state.phase == ApkInstallUiState.Phase.READY)) {
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
                                    onClick = ::beginAdbInstall,
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
                        ApkInstallUiState.Phase.RESULT, ApkInstallUiState.Phase.ERROR -> {
                            Row(modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                if (state.outcome == ApkInstallUiState.Outcome.SUCCESS &&
                                    state.packageName != packageName) {
                                    val launch = remember(state.packageName) {
                                        packageManager.getLaunchIntentForPackage(state.packageName)
                                    }
                                    if (launch != null) {
                                        FilledTonalButton(onClick = {
                                            if (!closing.value) {
                                                try {
                                                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    startActivity(launch)
                                                    closeInstaller()
                                                } catch (failure: Exception) {
                                                    appLog.append("APK installer: launch failed: " +
                                                        failure.javaClass.simpleName)
                                                }
                                            }
                                        }, enabled = !closing.value, modifier = Modifier.weight(1f)) {
                                            Text("Open app")
                                        }
                                    }
                                }
                                Button(onClick = close, enabled = !closing.value,
                                    modifier = Modifier.weight(1f)) {
                                    if (closing.value) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp)
                                        Text("Closing…")
                                    } else Text(if (state.outcome == ApkInstallUiState.Outcome.SUCCESS)
                                        "Done" else "Close")
                                }
                            }
                        }
                        ApkInstallUiState.Phase.INSTALLING ->
                            TextButton(onClick = close) { Text("Close · continue in background") }
                        else -> Unit
                    }
                }
            }
        }
    }

    /** Dismiss only the standalone installer; never navigate to LocalADB's main activity. */
    private fun closeInstaller() {
        if (closing.value) return
        closing.value = true
        appLog.append("APK popup: closing requested")
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { model.abandon() }
            } catch (failure: Exception) {
                appLog.append("APK installer close: cleanup failed: ${failure.javaClass.simpleName}")
            } finally {
                appLog.append("APK popup: close finished")
                if (isTaskRoot) finishAndRemoveTask() else finish()
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
            model.installing(native = true)
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

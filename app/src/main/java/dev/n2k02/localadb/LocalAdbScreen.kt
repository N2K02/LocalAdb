package dev.n2k02.localadb

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.n2k02.updater.N2K02UpdateBadgedIcon
import dev.n2k02.updater.N2K02UpdateBadge
import dev.n2k02.updater.N2K02UpdateScreen
import dev.n2k02.updater.hasPendingUpdate

private fun iconFor(page: Page): ImageVector = when (page) {
    Page.HOME -> Icons.Rounded.Home
    Page.SETTINGS -> Icons.Rounded.Settings
    Page.DIAGNOSTICS -> Icons.Rounded.BugReport
    Page.LOGS -> Icons.Rounded.Description
    Page.UPDATES -> Icons.Rounded.SystemUpdate
}

private fun titleFor(page: Page): String = when (page) {
    Page.HOME -> "Home"
    Page.SETTINGS -> "Settings"
    Page.DIAGNOSTICS -> "Diagnostics"
    Page.LOGS -> "Logs"
    Page.UPDATES -> "About & updates"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalAdbScreen(onQuit: () -> Unit) {
    var page by rememberSaveable { mutableStateOf(Page.HOME) }
    BackHandler(enabled = page != Page.HOME) { page = Navigation.back(page) }
    val status by LiveUpdater.controller.status.collectAsState()

    fun navigate(next: Page) {
        appLog.append("Navigate to $next")
        page = next
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(iconFor(page), contentDescription = null)
                        Text(titleFor(page), style = MaterialTheme.typography.titleLarge)
                    }
                },
                actions = {
                    Icon(Icons.Rounded.Code, contentDescription = null)
                    Text("LocalAdb", modifier = Modifier.padding(end = 16.dp),
                        style = MaterialTheme.typography.titleMedium)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    selected = page == Page.HOME,
                    onClick = { navigate(Navigation.selectTab(page, Page.HOME)) },
                    icon = { Icon(Icons.Rounded.Home, contentDescription = null) },
                    label = { Text("Home") },
                )
                NavigationBarItem(
                    selected = page != Page.HOME,
                    onClick = { navigate(Navigation.selectTab(page, Page.SETTINGS)) },
                    icon = {
                        N2K02UpdateBadgedIcon(status) {
                            Icon(Icons.Rounded.Settings, contentDescription = "Settings")
                        }
                    },
                    label = { Text("Settings") },
                )
                NavigationBarItem(
                    selected = false,
                    onClick = onQuit,
                    icon = { Icon(Icons.Rounded.PowerSettingsNew, contentDescription = "Quit LocalAdb") },
                    label = { Text("Quit") },
                )
            }
        },
    ) { inset ->
        when (page) {
            Page.HOME -> HomePage(Modifier.padding(inset))
            Page.SETTINGS -> SettingsPage(
                modifier = Modifier.padding(inset),
                hasUpdate = status.hasPendingUpdate(),
                onNavigate = ::navigate,
            )
            Page.DIAGNOSTICS -> DiagnosticsPage(Modifier.padding(inset))
            Page.LOGS -> LogsPage(Modifier.padding(inset))
            Page.UPDATES -> UpdatesPage(Modifier.padding(inset))
        }
    }
}

@Composable
private fun HomePage(modifier: Modifier) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("LocalAdb", style = MaterialTheme.typography.headlineMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Template ready", style = MaterialTheme.typography.titleLarge)
                Text("An Android starter with shared theme, updates, diagnostics and logs.")
                Text("ADB status: Not connected. Pairing, shell access and tunnelling are not implemented.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SettingsPage(modifier: Modifier, hasUpdate: Boolean, onNavigate: (Page) -> Unit) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("General", style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
        ListItem(
            headlineContent = { Text("App information") },
            supportingContent = { Text("LocalAdb starter application") },
            leadingContent = { Icon(Icons.Rounded.Code, contentDescription = null) },
        )
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        ListItem(
            headlineContent = { Text("Diagnostics") },
            supportingContent = { Text("Android version, package and build") },
            leadingContent = { Icon(Icons.Rounded.BugReport, contentDescription = null) },
            modifier = Modifier.clickable { onNavigate(Page.DIAGNOSTICS) },
        )
        ListItem(
            headlineContent = { Text("Logs") },
            supportingContent = { Text("Enable, copy and clear redacted logs") },
            leadingContent = { Icon(Icons.Rounded.Description, contentDescription = null) },
            modifier = Modifier.clickable { onNavigate(Page.LOGS) },
        )
        ListItem(
            headlineContent = { Text("About & updates") },
            supportingContent = { Text(if (hasUpdate) "An update is available" else "Version and release channels") },
            leadingContent = { Icon(Icons.Rounded.SystemUpdate, contentDescription = null) },
            trailingContent = { if (hasUpdate) N2K02UpdateBadge(LiveUpdater.controller.status.collectAsState().value) },
            modifier = Modifier.clickable { onNavigate(Page.UPDATES) },
        )
    }
}

@Composable
private fun DiagnosticsPage(modifier: Modifier) {
    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("System status", style = MaterialTheme.typography.titleLarge)
        ListItem(headlineContent = { Text("Android API") }, supportingContent = { Text("${Build.VERSION.SDK_INT}") })
        ListItem(headlineContent = { Text("Package") }, supportingContent = { Text(context.packageName) })
        ListItem(headlineContent = { Text("Build") }, supportingContent = { Text(version) })
        ListItem(headlineContent = { Text("ADB") }, supportingContent = { Text("Not configured in this template") })
        Text("The Quit action cancels updater work, disables logging and finishes the task.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LogsPage(modifier: Modifier) {
    val context = LocalContext.current
    val enabled by appLog.enabled.collectAsState()
    val revision by appLog.revision.collectAsState()
    val logLines = androidx.compose.runtime.remember(revision) { appLog.snapshot() }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Logs", style = MaterialTheme.typography.titleLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Enable in-app logging")
            Switch(checked = enabled, onCheckedChange = appLog::setEnabled)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val content = appLog.snapshot().joinToString("\n")
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("LocalAdb logs", content))
            }, enabled = logLines.isNotEmpty()) { Text("Copy") }
            TextButton(onClick = appLog::clear, enabled = logLines.isNotEmpty()) { Text("Clear") }
        }
        Text("In-memory only; sensitive values are redacted. Logging is off by default.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(Modifier.fillMaxWidth()) {
            Text(if (logLines.isEmpty()) "No logs" else logLines.joinToString("\n"),
                modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun UpdatesPage(modifier: Modifier) {
    val experimentalEnabled by LiveUpdater.experimentalEnabled.collectAsState()
    N2K02UpdateScreen(
        controller = LiveUpdater.controller,
        installGuard = LiveUpdater.installGuard,
        modifier = modifier.padding(16.dp),
        experimentalEnabled = experimentalEnabled,
        onExperimentalEnabledChange = LiveUpdater::setExperimentalEnabled,
    )
}

package dev.n2k02.localadb

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.n2k02.updater.N2K02UpdateBadgedIcon
import dev.n2k02.updater.N2K02UpdateBadge
import dev.n2k02.updater.N2K02UpdateScreen
import dev.n2k02.updater.hasPendingUpdate

private fun iconFor(page: Page): ImageVector = when (page) {
    Page.HOME -> Icons.Rounded.Home
    Page.ADB -> Icons.Rounded.Terminal
    Page.SETTINGS -> Icons.Rounded.Settings
    Page.ADB_CONNECTION -> Icons.Rounded.Wifi
    Page.APPS -> Icons.Rounded.Apps
    Page.UPDATES -> Icons.Rounded.SystemUpdate
}

private fun titleFor(page: Page): String = when (page) {
    Page.HOME -> "Home"
    Page.ADB -> "Adb"
    Page.SETTINGS -> "Settings"
    Page.ADB_CONNECTION -> "ADB connection"
    Page.APPS -> "Apps"
    Page.UPDATES -> "Updates"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalAdbScreen(onQuit: () -> Unit) {
    var page by rememberSaveable { mutableStateOf(Page.HOME) }
    BackHandler(enabled = page != Page.HOME) { page = Navigation.back(page) }
    val updates by LiveUpdater.controller.status.collectAsState()

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
                    Icon(painterResource(R.drawable.ic_localadb), contentDescription = null)
                    Text("LocalAdb", modifier = Modifier.padding(end = 16.dp),
                        style = MaterialTheme.typography.titleMedium)
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    selected = page == Page.HOME,
                    onClick = { page = Page.HOME },
                    icon = { Icon(Icons.Rounded.Home, contentDescription = null) },
                    label = { Text("Home") })
                NavigationBarItem(
                    selected = page == Page.ADB,
                    onClick = { page = Page.ADB },
                    icon = { Icon(Icons.Rounded.Terminal, contentDescription = "ADB shell") },
                    label = { Text("Adb") })
                NavigationBarItem(
                    selected = page !in listOf(Page.HOME, Page.ADB),
                    onClick = { page = Page.SETTINGS },
                    icon = {
                        N2K02UpdateBadgedIcon(updates) {
                            Icon(Icons.Rounded.Settings, contentDescription = "Settings")
                        }
                    },
                    label = { Text("Settings") })
                NavigationBarItem(
                    selected = false,
                    onClick = onQuit,
                    icon = { Icon(Icons.Rounded.PowerSettingsNew, contentDescription = "Quit LocalAdb") },
                    label = { Text("Quit") })
            }
        }
    ) { inset ->
        when (page) {
            Page.HOME -> HomePage(Modifier.padding(inset),
                onOpenAdb = { page = Page.ADB_CONNECTION },
                onOpenShell = { page = Page.ADB },
                onOpenApps = { page = Page.APPS })
            Page.ADB -> AdbConsolePage(Modifier.padding(inset)) {
                page = Page.ADB_CONNECTION
            }
            Page.SETTINGS -> SettingsPage(Modifier.padding(inset), updates.hasPendingUpdate()) {
                page = it
            }
            Page.ADB_CONNECTION -> AdbConnectionPage(Modifier.padding(inset))
            Page.APPS -> AppsPage(Modifier.padding(inset))
            Page.UPDATES -> UpdatesPage(Modifier.padding(inset))
        }
    }
}

@Composable
private fun HomePage(
    modifier: Modifier,
    onOpenAdb: () -> Unit,
    onOpenShell: () -> Unit,
    onOpenApps: () -> Unit,
) {
    val connection by AdbEngine.status.collectAsState()
    val shizuku by NativeShizukuManager.state.collectAsState()
    val permissions by AdbPermissionBootstrap.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("System status", style = MaterialTheme.typography.titleLarge)
                Text(
                    if (connection.connected) "ADB ready · verified shell UID 2000"
                    else "ADB not connected",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    HomeStatus("ADB", if (connection.connected) "Ready" else "Offline",
                        Modifier.weight(1f))
                    HomeStatus("Pairing", if (connection.pairingPort != null) "Found"
                        else if (connection.connected) "Paired" else "Waiting", Modifier.weight(1f))
                    HomeStatus("Transport", if (connection.tcpConnected) "TCP"
                        else if (connection.connected) "TLS" else "—", Modifier.weight(1f))
                    HomeStatus("Shell UID", if (connection.connected) "2000" else "—",
                        Modifier.weight(1f))
                }
                Text(connection.message, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { AdbEngine.start(context, explicit = true) }) {
                        Text(if (connection.connected) "Retry" else "Connect")
                    }
                    OutlinedButton(onClick = onOpenAdb) { Text("ADB settings") }
                }
            }
        }

        Card(Modifier.fillMaxWidth().clickable(onClick = onOpenApps)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Security, contentDescription = null)
                    Text("Native Shizuku server", style = MaterialTheme.typography.titleMedium)
                }
                Text(if (shizuku.serviceReady) "Ready · shell UID 2000"
                    else if (shizuku.starting) "Starting embedded server…"
                    else "Not running")
                Text(shizuku.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Manage Shizuku app approvals",
                    color = MaterialTheme.colorScheme.primary)
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.VerifiedUser, contentDescription = null)
                    Text("Automatic Android permissions",
                        style = MaterialTheme.typography.titleMedium)
                }
                Text(if (!permissions.finished) "Waiting for verified ADB shell"
                    else permissions.granted.size.toString() + " granted · " +
                        permissions.unavailable.size + " unavailable")
                if (permissions.unavailable.isNotEmpty())
                    Text(permissions.unavailable.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall)
            }
        }

        Card(Modifier.fillMaxWidth().clickable(onClick = onOpenShell)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Terminal, contentDescription = null)
                    Text("ADB commands", style = MaterialTheme.typography.titleMedium)
                }
                Text("Run commands on this device with the verified UID 2000 shell.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Open command console", color = MaterialTheme.colorScheme.primary)
            }
        }

        Card(Modifier.fillMaxWidth().clickable(onClick = onOpenApps)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Apps, contentDescription = null)
                    Text("App access", style = MaterialTheme.typography.titleMedium)
                }
                Text("Android permission grants and existing Shizuku manager access.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Open Apps", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun HomeStatus(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            Modifier.padding(vertical = 12.dp, horizontal = 2.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(label, maxLines = 1, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Text(value, maxLines = 1, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun SettingsPage(modifier: Modifier, hasUpdate: Boolean, onNavigate: (Page) -> Unit) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("General", style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
        ListItem(
            headlineContent = { Text("ADB connection") },
            supportingContent = { Text("Pairing, verified shell, automatic recovery and TCP/5555") },
            leadingContent = { Icon(Icons.Rounded.Wifi, contentDescription = null) },
            modifier = Modifier.clickable { onNavigate(Page.ADB_CONNECTION) })
        ListItem(
            headlineContent = { Text("Apps") },
            supportingContent = { Text("Android developer permissions and Shizuku-compatible apps") },
            leadingContent = { Icon(Icons.Rounded.Apps, contentDescription = null) },
            modifier = Modifier.clickable { onNavigate(Page.APPS) })
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        ListItem(
            headlineContent = { Text("Updates") },
            supportingContent = { Text(if (hasUpdate) "An update is available" else "Version and release channels") },
            leadingContent = { Icon(Icons.Rounded.SystemUpdate, contentDescription = null) },
            trailingContent = {
                if (hasUpdate) N2K02UpdateBadge(LiveUpdater.controller.status.collectAsState().value)
            },
            modifier = Modifier.clickable { onNavigate(Page.UPDATES) })
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

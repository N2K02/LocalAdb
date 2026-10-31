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
import androidx.compose.ui.unit.dp
import dev.n2k02.updater.N2K02UpdateBadgedIcon
import dev.n2k02.updater.N2K02UpdateBadge
import dev.n2k02.updater.N2K02UpdateScreen
import dev.n2k02.updater.hasPendingUpdate

private fun iconFor(page: Page): ImageVector = when (page) {
    Page.HOME -> Icons.Rounded.Home
    Page.SETTINGS -> Icons.Rounded.Settings
    Page.ADB -> Icons.Rounded.Wifi
    Page.APPS -> Icons.Rounded.Apps
    Page.UPDATES -> Icons.Rounded.SystemUpdate
}
private fun titleFor(page: Page): String = when (page) {
    Page.HOME -> "Home"
    Page.SETTINGS -> "Settings"
    Page.ADB -> "ADB connection"
    Page.APPS -> "Apps"
    Page.UPDATES -> "Updates"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalAdbScreen(onQuit: () -> Unit) {
    var page by rememberSaveable { mutableStateOf(Page.HOME) }
    BackHandler(enabled = page != Page.HOME) { page = Navigation.back(page) }
    val updates by LiveUpdater.controller.status.collectAsState()
    fun navigate(next: Page) { page = next }

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
                    onClick = { navigate(Page.HOME) },
                    icon = { Icon(Icons.Rounded.Home, contentDescription = null) },
                    label = { Text("Home") })
                NavigationBarItem(
                    selected = page != Page.HOME,
                    onClick = { navigate(Page.SETTINGS) },
                    icon = {
                        N2K02UpdateBadgedIcon(updates) {
                            Icon(Icons.Rounded.Settings, contentDescription = "Settings")
                        }
                    },
                    label = { Text("Settings") })
                NavigationBarItem(
                    selected = false, onClick = onQuit,
                    icon = { Icon(Icons.Rounded.PowerSettingsNew, contentDescription = "Quit LocalAdb") },
                    label = { Text("Quit") })
            }
        }
    ) { inset ->
        when (page) {
            Page.HOME -> HomePage(
                Modifier.padding(inset),
                onOpenAdb = { navigate(Page.ADB) },
                onOpenApps = { navigate(Page.APPS) }
            )
            Page.SETTINGS -> SettingsPage(Modifier.padding(inset), updates.hasPendingUpdate(), ::navigate)
            Page.ADB -> AdbConnectionPage(Modifier.padding(inset))
            Page.APPS -> AppsPage(Modifier.padding(inset))
            Page.UPDATES -> UpdatesPage(Modifier.padding(inset))
        }
    }
}

@Composable
private fun HomePage(modifier: Modifier, onOpenAdb: () -> Unit, onOpenApps: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val connection by AdbEngine.status.collectAsState()
    val permissions by AdbPermissionBootstrap.status.collectAsState()
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("System Status", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (connection.connected) "Ready · ADB shell UID 2000 verified"
                    else "Not ready · authorize local Android debugging",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    HomeStat("Connection", if (connection.connected) "Ready" else "Offline", Modifier.weight(1f))
                    HomeStat("Pairing", if (connection.pairingPort != null) "Found" else if (connection.connected) "Paired" else "Waiting", Modifier.weight(1f))
                    HomeStat("Recovery", if (connection.scanning) "Scan" else if (connection.connected) "Ready" else "Standby", Modifier.weight(1f))
                    HomeStat("Shell UID", if (connection.connected) "2000" else "—", Modifier.weight(1f))
                }
                Text(connection.message, style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { AdbEngine.start(context, explicit = true) },
                        modifier = Modifier.weight(1f)
                    ) { Text(if (connection.connected) "Refresh" else "Start") }
                    OutlinedButton(
                        onClick = { AdbEngine.shutdown(context) },
                        modifier = Modifier.weight(1f)
                    ) { Text("Stop") }
                    OutlinedButton(
                        onClick = {
                            AdbEngine.shutdown(context)
                            AdbEngine.start(context, explicit = true)
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Restart") }
                }
            }
        }
        Card(Modifier.fillMaxWidth().clickable(onClick = onOpenAdb)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.Wifi, contentDescription = null)
                    Text("ADB connection", style = MaterialTheme.typography.titleMedium)
                }
                Text(if (connection.tcpConnected) "Transport · TCP/5555"
                    else if (connection.connected) "Transport · Wireless TLS"
                    else "Pair with Android Wireless debugging")
                Text("Pairing, watchdog, boot recovery and TCP settings",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.VerifiedUser, contentDescription = null)
                    Text("Automatic permissions", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    when {
                        permissions.busy -> "Configuring Android permissions through shell UID 2000…"
                        !permissions.finished -> "Waiting for verified shell UID 2000"
                        permissions.unavailable.isEmpty() -> "Verified · " + permissions.granted.size + " eligible permissions ready"
                        else -> permissions.granted.size.toString() + " granted · " +
                            permissions.unavailable.size + " need attention"
                    }
                )
                if (permissions.unavailable.isNotEmpty()) {
                    Text(permissions.unavailable.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Card(Modifier.fillMaxWidth().clickable(onClick = onOpenApps)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.Apps, contentDescription = null)
                    Text("App access", style = MaterialTheme.typography.titleMedium)
                }
                Text("Manage eligible Android permissions and open Shizuku authorization")
                Text("Open Apps", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun HomeStat(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(Modifier.padding(vertical = 12.dp, horizontal = 3.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall,
                maxLines = 1, modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text(value, style = MaterialTheme.typography.labelMedium,
                maxLines = 1, modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable
private fun SettingsPage(modifier: Modifier, hasUpdate: Boolean, onNavigate: (Page) -> Unit) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("General", style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
        ListItem(
            headlineContent = { Text("ADB connection") },
            supportingContent = { Text("Pairing, verified shell, automatic recovery and TCP/5555") },
            leadingContent = { Icon(Icons.Rounded.Wifi, contentDescription = null) },
            modifier = Modifier.clickable { onNavigate(Page.ADB) })
        ListItem(
            headlineContent = { Text("Apps") },
            supportingContent = { Text("Shizuku-compatible apps and verified Android permission grants") },
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
        onExperimentalEnabledChange = LiveUpdater::setExperimentalEnabled
    )
}

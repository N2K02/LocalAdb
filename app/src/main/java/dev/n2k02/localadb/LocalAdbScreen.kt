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
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
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
    Page.APPS -> Icons.Rounded.Apps
    Page.ADB -> Icons.Rounded.Terminal
    Page.SETTINGS -> Icons.Rounded.Settings
    Page.ADB_CONNECTION -> Icons.Rounded.Wifi
    Page.UPDATES -> Icons.Rounded.SystemUpdate
}

private fun titleFor(page: Page): String = when (page) {
    Page.HOME -> "Home"
    Page.APPS -> "Apps"
    Page.ADB -> "Adb"
    Page.SETTINGS -> "Settings"
    Page.ADB_CONNECTION -> "ADB connection"
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(iconFor(page), contentDescription = null,
                            modifier = Modifier.size(24.dp))
                        Text(titleFor(page), style = MaterialTheme.typography.titleLarge,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                actions = {
                    Row(
                        modifier = Modifier.padding(end = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Constrain the 108dp launcher vector; otherwise it hides page titles.
                        Icon(painterResource(R.drawable.ic_localadb),
                            contentDescription = "LocalAdb",
                            modifier = Modifier.size(25.dp), tint = Color.Unspecified)
                        Text("LocalAdb", style = MaterialTheme.typography.labelLarge,
                            maxLines = 1)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    selected = page == Page.HOME,
                    onClick = { page = Navigation.selectTab(page, Page.HOME) },
                    icon = { Icon(Icons.Rounded.Home, contentDescription = null) },
                    label = { Text("Home") })
                NavigationBarItem(
                    selected = page == Page.APPS,
                    onClick = { page = Navigation.selectTab(page, Page.APPS) },
                    icon = { Icon(Icons.Rounded.Apps, contentDescription = null) },
                    label = { Text("Apps") })
                NavigationBarItem(
                    selected = page == Page.ADB,
                    onClick = { page = Navigation.selectTab(page, Page.ADB) },
                    icon = { Icon(Icons.Rounded.Terminal, contentDescription = "ADB shell") },
                    label = { Text("Adb") })
                NavigationBarItem(
                    selected = page in listOf(Page.SETTINGS, Page.ADB_CONNECTION, Page.UPDATES),
                    onClick = { page = Navigation.selectTab(page, Page.SETTINGS) },
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
            Page.HOME -> HomePage(Modifier.padding(inset))
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
private fun HomePage(modifier: Modifier) {
    val connection by AdbEngine.status.collectAsState()
    val shizuku by NativeShizukuManager.state.collectAsState()
    val permissions by AdbPermissionBootstrap.state.collectAsState()

    // Home is read-only. The Apps tab, Adb tab and Settings own their actions.
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Wifi, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary)
                        Text("ADB connection", style = MaterialTheme.typography.titleMedium)
                    }
                    StatusLabel(if (connection.connected) "Ready" else "Offline",
                        connection.connected)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HomeStatus("Shell", if (connection.connected) "2000" else "—",
                        Modifier.weight(1f))
                    HomeStatus("Pairing", when {
                        connection.connected -> "Paired"
                        connection.pairingPort != null -> "Found"
                        else -> "Waiting"
                    }, Modifier.weight(1f))
                    HomeStatus("Transport", when {
                        connection.tcpConnected -> "TCP"
                        connection.connected -> "TLS"
                        else -> "—"
                    }, Modifier.weight(1f))
                    HomeStatus("Discovery", if (connection.scanning) "Scan" else "Idle",
                        Modifier.weight(1f))
                }
                Text(connection.message, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Security, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary)
                        Text("Shizuku server", style = MaterialTheme.typography.titleMedium)
                    }
                    StatusLabel(when {
                        shizuku.serviceReady -> "Ready"
                        shizuku.starting -> "Starting"
                        else -> "Offline"
                    }, shizuku.serviceReady)
                }
                Text(shizuku.message, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.VerifiedUser, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary)
                    Text("Android permissions", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    if (!permissions.finished) "Waiting for verified shell UID 2000"
                    else permissions.granted.size.toString() + " granted · " +
                        permissions.unavailable.size + " unavailable",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (permissions.unavailable.isNotEmpty()) {
                    Text(permissions.unavailable.joinToString(" · ") {
                        it.substringAfterLast('.')
                    }, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun StatusLabel(label: String, isReady: Boolean) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (isReady) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (isReady) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
    }
}

@Composable
private fun HomeStatus(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(Modifier.padding(vertical = 10.dp, horizontal = 2.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, maxLines = 1, style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center)
            Text(value, maxLines = 1, style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center)
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

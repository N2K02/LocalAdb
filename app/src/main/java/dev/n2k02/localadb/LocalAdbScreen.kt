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
    Page.UPDATES -> Icons.Rounded.SystemUpdate
}
private fun titleFor(page: Page): String = when (page) {
    Page.HOME -> "Home"
    Page.SETTINGS -> "Settings"
    Page.ADB -> "ADB connection"
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
            Page.HOME -> HomePage(Modifier.padding(inset), onOpenAdb = { navigate(Page.ADB) })
            Page.SETTINGS -> SettingsPage(Modifier.padding(inset), updates.hasPendingUpdate(), ::navigate)
            Page.ADB -> AdbConnectionPage(Modifier.padding(inset))
            Page.UPDATES -> UpdatesPage(Modifier.padding(inset))
        }
    }
}

@Composable
private fun HomePage(modifier: Modifier, onOpenAdb: () -> Unit) {
    val connection by AdbEngine.status.collectAsState()
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth().clickable(onClick = onOpenAdb)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Local ADB", style = MaterialTheme.typography.titleLarge)
                Text(if (connection.connected) "Connected · shell UID 2000"
                    else "Not connected · Set up Wireless debugging")
                Text(connection.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Open ADB connection", color = MaterialTheme.colorScheme.primary)
            }
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

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.n2k02.updater.N2K02UpdateBadgedIcon
import dev.n2k02.updater.N2K02UpdateBadge
import dev.n2k02.updater.N2K02UpdateScreen
import dev.n2k02.updater.hasPendingUpdate

private fun iconFor(page: Page): ImageVector = when (page) {
    Page.HOME -> Icons.Rounded.Home
    Page.APPS -> Icons.Rounded.Apps
    Page.ADB -> Icons.Rounded.Terminal
    Page.COMMAND_LIBRARY -> Icons.Rounded.MenuBook
    Page.SETTINGS -> Icons.Rounded.Settings
    Page.ADB_CONNECTION -> Icons.Rounded.Wifi
    Page.UPDATES -> Icons.Rounded.SystemUpdate
}

private fun titleFor(page: Page): String = when (page) {
    Page.HOME -> "Home"
    Page.APPS -> "Apps"
    Page.ADB -> "Adb"
    Page.COMMAND_LIBRARY -> "Commands"
    Page.SETTINGS -> "Settings"
    Page.ADB_CONNECTION -> "ADB connection"
    Page.UPDATES -> "Updates"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalAdbScreen(onQuit: () -> Unit) {
    var page by rememberSaveable { mutableStateOf(Page.HOME) }
    // Hoist the unsent draft so opening the Commands submenu never loses text.
    var adbDraft by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    // Main tabs remember actual visit order, rather than always returning Home.
    var visitedTabs by rememberSaveable { mutableStateOf(listOf(Page.HOME.name)) }
    val mainTabs = listOf(Page.HOME, Page.APPS, Page.ADB, Page.SETTINGS)

    fun selectMainTab(tab: Page) {
        val target = Navigation.selectTab(page, tab)
        visitedTabs = TabVisitHistory.select(visitedTabs, target.name)
        page = target
    }

    BackHandler(enabled = page != Page.HOME || visitedTabs.size > 1) {
        when (page) {
            Page.COMMAND_LIBRARY, Page.ADB_CONNECTION, Page.UPDATES -> {
                val parent = Navigation.back(page)
                if (parent != Page.valueOf(visitedTabs.last())) {
                    visitedTabs = TabVisitHistory.select(visitedTabs, parent.name)
                }
                page = parent
            }
            else -> {
                val (remaining, previous) = TabVisitHistory.pop(visitedTabs)
                if (previous != null) {
                    visitedTabs = remaining
                    page = Page.valueOf(previous)
                } else {
                    page = Page.HOME
                }
            }
        }
    }
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
                    onClick = { selectMainTab(Page.HOME) },
                    icon = { Icon(Icons.Rounded.Home, contentDescription = null) },
                    label = { Text("Home") })
                NavigationBarItem(
                    selected = page == Page.APPS,
                    onClick = { selectMainTab(Page.APPS) },
                    icon = { Icon(Icons.Rounded.Apps, contentDescription = null) },
                    label = { Text("Apps") })
                NavigationBarItem(
                    selected = page == Page.ADB || page == Page.COMMAND_LIBRARY,
                    onClick = { selectMainTab(Page.ADB) },
                    icon = { Icon(Icons.Rounded.Terminal, contentDescription = "ADB shell") },
                    label = { Text("Adb") })
                NavigationBarItem(
                    selected = page in listOf(Page.SETTINGS, Page.ADB_CONNECTION, Page.UPDATES),
                    onClick = { selectMainTab(Page.SETTINGS) },
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
        // A passive gesture waits for a real horizontal swipe and leaves ordinary
        // scrolling and input alone. ADB's nested output tabs take precedence.
        val currentIndex = mainTabs.indexOf(page)
        val pageModifier = if (currentIndex >= 0) {
            Modifier.padding(inset).swipeTabNavigation(
                onSwipeLeft = { if (currentIndex < mainTabs.lastIndex)
                    selectMainTab(mainTabs[currentIndex + 1]) },
                onSwipeRight = { if (currentIndex > 0)
                    selectMainTab(mainTabs[currentIndex - 1]) },
                canSwipeLeft = currentIndex < mainTabs.lastIndex,
                canSwipeRight = currentIndex > 0,
            )
        } else Modifier.padding(inset)
        when (page) {
            Page.HOME -> HomePage(pageModifier)
            Page.ADB -> AdbConsolePage(
                modifier = pageModifier,
                input = adbDraft,
                onInputChange = { adbDraft = it },
                onOpenConnection = { page = Page.ADB_CONNECTION },
                onOpenCommands = { page = Page.COMMAND_LIBRARY },
            )
            Page.COMMAND_LIBRARY -> CommandLibraryPage(
                modifier = Modifier.padding(inset),
                onBack = { page = Page.ADB },
                onUse = { selected ->
                    adbDraft = TextFieldValue(selected,
                        selection = TextRange(selected.length))
                    page = Page.ADB
                },
            )
            Page.SETTINGS -> SettingsPage(pageModifier, updates.hasPendingUpdate()) {
                page = it
            }
            Page.ADB_CONNECTION -> AdbConnectionPage(Modifier.padding(inset))
            Page.APPS -> AppsPage(pageModifier)
            Page.UPDATES -> UpdatesPage(Modifier.padding(inset))
        }
    }
}

@Composable
private fun HomePage(modifier: Modifier) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var automationEnabled by remember { mutableStateOf(ShizukuIntentAccess.isEnabled(context)) }
    var automationToken by remember { mutableStateOf("") }
    val connection by AdbEngine.status.collectAsState()
    val shizuku by NativeShizukuManager.state.collectAsState()
    val helperTest by LocalHelperDiagnostics.state.collectAsState()
    val permissions by AdbPermissionBootstrap.state.collectAsState()
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    var shizukuExpanded by rememberSaveable { mutableStateOf(false) }
    var appCounts by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    // Count real eligible installed apps, not invented screenshot values.
    // Returning to Home re-enters composition and refreshes the live counts.
    LaunchedEffect(shizuku.serviceReady) {
        if (!shizuku.serviceReady) LocalHelperDiagnostics.cancel()
        appCounts = null
        try {
            val available = withContext(Dispatchers.IO) {
                AppAccessRepository.list(context.applicationContext)
            }
            appCounts = available.count { it.shizukuClient } to
                available.sumOf { it.grantedAdbPermissions.size }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Counts stay unavailable; never claim zero clients after a scan error.
        }
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        ModernPanel(Modifier.fillMaxWidth(), DashboardTone.INFO, compact = true) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                ModernSectionHeader(
                    title = "ADB connection", icon = Icons.Rounded.Wifi,
                    tone = DashboardTone.INFO,
                    subtitle = if (connection.connected) "Authorized shell · UID 2000"
                    else "Waiting for an authorized Android shell",
                    status = if (connection.connected) "Ready" else "Offline",
                    statusTone = if (connection.connected) DashboardTone.GOOD
                    else DashboardTone.ERROR,
                    compact = true,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    HomeMetric("Shell", if (connection.connected) "2000" else "—",
                        Icons.Rounded.Terminal, Modifier.weight(1f),
                        if (connection.connected) DashboardTone.GOOD else DashboardTone.WARN)
                    HomeMetric("Pairing", when {
                        connection.connected -> "Paired"
                        connection.pairingPort != null -> "Found"
                        else -> "Waiting"
                    }, Icons.Rounded.Link, Modifier.weight(1f),
                        if (connection.connected) DashboardTone.GOOD else DashboardTone.WARN)
                    HomeMetric("Transport", when {
                        connection.tcpConnected -> "TCP"
                        connection.connected -> "TLS"
                        else -> "—"
                    }, Icons.Rounded.SettingsEthernet, Modifier.weight(1f))
                    HomeMetric("Discovery", if (connection.scanning) "Scan" else "Idle",
                        Icons.Rounded.Radar, Modifier.weight(1f), DashboardTone.NEUTRAL)
                }
                if (!connection.connected) {
                    Text(connection.message, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        AdbHomeSetupCard()

        ModernPanel(Modifier.fillMaxWidth(), DashboardTone.INFO, compact = true) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                ModernSectionHeader(
                    title = "Shizuku manager", icon = Icons.Rounded.Security,
                    tone = DashboardTone.INFO,
                    subtitle = if (shizuku.serviceReady) "Embedded Shizuku server · UID 2000"
                        else shizuku.message,
                    status = when {
                        shizuku.serviceReady -> "Ready"
                        shizuku.starting -> "Starting"
                        shizuku.stopping -> "Stopping"
                        shizuku.manuallyStopped -> "Stopped"
                        else -> "Offline"
                    },
                    statusTone = when {
                        shizuku.serviceReady -> DashboardTone.GOOD
                        shizuku.starting || shizuku.stopping -> DashboardTone.WARN
                        else -> DashboardTone.ERROR
                    },
                    compact = true,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { shizukuExpanded = !shizukuExpanded },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    Text(if (shizukuExpanded) "Collapse" else "Expand",
                        style = MaterialTheme.typography.labelMedium)
                    Icon(if (shizukuExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = if (shizukuExpanded) "Collapse Shizuku manager" else "Expand Shizuku manager")
                }
                if (shizukuExpanded) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    HomeMetric("Binder", if (shizuku.serviceReady) "Active" else "Offline",
                        Icons.Rounded.Dns, Modifier.weight(1f),
                        if (shizuku.serviceReady) DashboardTone.GOOD else DashboardTone.WARN)
                    HomeMetric("Shell", if (shizuku.serviceReady) "2000" else "—",
                        Icons.Rounded.VerifiedUser, Modifier.weight(1f),
                        if (shizuku.serviceReady) DashboardTone.GOOD else DashboardTone.NEUTRAL)
                    HomeMetric("Clients", appCounts?.first?.toString() ?: "—",
                        Icons.Rounded.Apps, Modifier.weight(1f))
                    HomeMetric("Grants", appCounts?.second?.toString() ?: "—",
                        Icons.Rounded.Key, Modifier.weight(1f))
                }
                // Three compact controls share one row; their full actions remain available.
                Row(Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp),
                        onClick = { NativeShizukuManager.stop(context) },
                        enabled = !shizuku.stopping &&
                            (connection.connected || shizuku.serviceReady || shizuku.starting)
                    ) {
                        Icon(Icons.Rounded.Stop, contentDescription = null,
                            modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Stop", maxLines = 1)
                    }
                    Button(
                        modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp),
                        onClick = { NativeShizukuManager.restart(context) },
                        enabled = connection.connected && !shizuku.starting && !shizuku.stopping
                    ) {
                        Icon(Icons.Rounded.RestartAlt, contentDescription = null,
                            modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Restart", maxLines = 1)
                    }
                    OutlinedButton(
                        modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp),
                        onClick = { LocalHelperDiagnostics.start(context) },
                        enabled = shizuku.serviceReady && !shizuku.starting &&
                            !shizuku.stopping && !helperTest.running
                    ) {
                        Text(if (helperTest.running) "Testing…" else "Test helper",
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (helperTest.running || helperTest.message != "LocalHelper not tested") {
                    Text(helperTest.message, style = MaterialTheme.typography.bodySmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (!shizuku.serviceReady || shizuku.manuallyStopped) Text(
                    if (shizuku.manuallyStopped) "Automatic startup paused until Restart."
                    else "Automatically starts when ADB verifies shell UID 2000.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Start / Stop intents", style = MaterialTheme.typography.titleSmall)
                        Text("Allow automation apps to control embedded Shizuku using a secret token.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = automationEnabled, onCheckedChange = { enabled ->
                        ShizukuIntentAccess.setEnabled(context, enabled)
                        automationEnabled = enabled
                        automationToken = if (enabled) ShizukuIntentAccess.token(context) else ""
                    })
                }
                if (automationEnabled) {
                    val token = automationToken.ifEmpty {
                        ShizukuIntentAccess.token(context).also { automationToken = it }
                    }
                    Text("Token (keep private)", style = MaterialTheme.typography.labelMedium)
                    SelectionContainer {
                        Text(token, fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            clipboard.setText(AnnotatedString(token))
                        }) { Text("Copy token") }
                        OutlinedButton(onClick = {
                            automationToken = ShizukuIntentAccess.rotateToken(context)
                        }) { Text("Regenerate") }
                    }
                    Text("Target package: " + context.packageName + " · String extra: auth",
                        style = MaterialTheme.typography.bodySmall)
                    SelectionContainer {
                        Text("START: " + ShizukuIntentAccess.ACTION_START +
                            "\\nSTOP: " + ShizukuIntentAccess.ACTION_STOP,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (shizuku.diagnostics.isNotBlank()) {
                    TextButton(onClick = { showDiagnostics = !showDiagnostics }) {
                        Text(if (showDiagnostics) "Hide server diagnostics"
                        else "Show server diagnostics")
                    }
                    if (showDiagnostics) {
                        SelectionContainer {
                            Text(shizuku.diagnostics,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                }
            }
        }

        ModernPanel(Modifier.fillMaxWidth(), DashboardTone.GOOD, compact = true) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                ModernSectionHeader(
                    title = "Android permissions",
                    subtitle = DashboardPresentation.permissionSummary(
                        permissions.finished, permissions.granted, permissions.unavailable),
                    icon = Icons.Rounded.VerifiedUser,
                    tone = DashboardTone.GOOD,
                    status = if (permissions.finished)
                        permissions.granted.size.toString() + " granted" else "Checking",
                    statusTone = if (permissions.finished && permissions.unavailable.isEmpty())
                        DashboardTone.GOOD else DashboardTone.WARN,
                    compact = true,
                )
                if (!permissions.finished) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    val all = permissions.granted.sorted().map { it to true } +
                        permissions.unavailable.sorted().map { it to false }
                    if (all.isEmpty()) {
                        Text("No automatic Android permissions are requested.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        // Horizontal scrolling preserves long permission names on narrow devices.
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            all.forEach { (permission, granted) ->
                                ModernStatusChip(
                                    DashboardPresentation.permissionName(permission),
                                    if (granted) DashboardTone.GOOD else DashboardTone.ERROR,
                                    compact = true,
                                    icon = if (granted) Icons.Rounded.CheckCircle
                                    else Icons.Rounded.ErrorOutline
                                )
                            }
                        }
                    }
                    val coverage = DashboardPresentation.permissionCoverage(
                        permissions.finished, permissions.granted.size,
                        permissions.unavailable.size
                    )
                    if (coverage != null && coverage < 1f) {
                        LinearProgressIndicator(
                            progress = { coverage },
                            modifier = Modifier.fillMaxWidth(),
                            color = if (coverage == 1f) DashboardTone.GOOD.accent
                                else DashboardTone.WARN.accent
                        )
                    }
                }
            }
        }
        AndroidAutoInstallerCard()

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

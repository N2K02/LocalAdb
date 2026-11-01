package dev.n2k02.localadb

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.ui.Alignment
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class PendingAndroidGrant(
    val app: AppAccess, val permission: String, val grant: Boolean,
)
private data class PendingShizukuGrant(val app: AppAccess, val grant: Boolean)

/**
 * LocalAdb is its OWN Shizuku manager: Binder-backed authorization is read/changed
 * through its own embedded server and confirmed by readback.
 * Android development permissions use the separate verified ADB shell.
 */
@Composable
internal fun AppsPage(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val adb by AdbEngine.status.collectAsState()
    val native by NativeShizukuManager.state.collectAsState()

    var apps by remember { mutableStateOf<List<AppAccess>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var search by remember { mutableStateOf("") }
    var selectedTypes by remember { mutableStateOf(setOf<String>()) }
    var selectedAccess by remember { mutableStateOf(setOf("Shizuku")) }
    var filtersOpen by remember { mutableStateOf(false) }
    var descending by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<PendingAndroidGrant?>(null) }
    var changing by remember { mutableStateOf<String?>(null) }
    var shizukuGrants by remember { mutableStateOf<Map<Int, Boolean?>>(emptyMap()) }
    var pendingShizuku by remember { mutableStateOf<PendingShizukuGrant?>(null) }
    var shizukuChanging by remember { mutableStateOf<Int?>(null) }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(refresh, native.serviceReady) {
        loading = true
        scanError = null
        NativeShizukuManager.refresh(context)
        try {
            val discovered = AppAccessRepository.list(context)
            apps = discovered
            // Binder readback and PackageManager lookups can block: keep them off the UI thread.
            shizukuGrants = withContext(Dispatchers.IO) {
                discovered.filter { it.shizukuClient }.associate { app ->
                    app.uid to NativeShizukuManager.granted(context, app.uid)
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            scanError = "Android app discovery failed: " +
                (e.localizedMessage ?: "try refreshing or checking package visibility")
        } finally {
            loading = false
        }
    }

    val visible = remember(apps, search, selectedTypes, selectedAccess, descending) {
        AppAccessPolicy.filterSelection(apps, search, selectedTypes, selectedAccess, descending)
    }
    val numberOfClients = apps.count { it.shizukuClient }
    val numberOfAndroidGrants = apps.sumOf { it.grantedAdbPermissions.size }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 13.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            // A single compact top card: search, live status and multi-select controls.
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, DashboardTone.INFO.accent.copy(alpha = 0.47f)),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(11.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    CompactSearchField(
                        value = search, onValueChange = { search = it },
                        placeholder = "Search apps or package name",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        ModernStatusChip(if (adb.connected) "Ready" else "Offline",
                            if (adb.connected) DashboardTone.GOOD else DashboardTone.ERROR,
                            icon = Icons.Rounded.CheckCircle)
                        ModernStatusChip(
                            if (adb.connected) {
                                if (adb.tcpConnected) "TCP/5555" else "Wireless TLS"
                            } else "Disconnected",
                            if (adb.connected) DashboardTone.GOOD else DashboardTone.WARN,
                            icon = null)
                        ModernStatusChip(if (adb.connected) "UID 2000" else "No shell",
                            if (adb.connected) DashboardTone.INFO else DashboardTone.WARN,
                            icon = null)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box {
                            OutlinedButton(
                                onClick = { filtersOpen = true },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                modifier = Modifier.height(36.dp),
                            ) {
                                Text("Filters · " + (selectedTypes.size + selectedAccess.size),
                                    style = MaterialTheme.typography.labelMedium)
                                Spacer(Modifier.width(3.dp))
                                Icon(Icons.Rounded.ExpandMore, contentDescription = null,
                                    modifier = Modifier.size(18.dp))
                            }
                            DropdownMenu(expanded = filtersOpen,
                                onDismissRequest = { filtersOpen = false }) {
                                Column(Modifier.heightIn(max = 390.dp)
                                    .verticalScroll(rememberScrollState())) {
                                    Text("App type", modifier = Modifier.padding(
                                        horizontal = 14.dp, vertical = 7.dp),
                                        style = MaterialTheme.typography.labelMedium)
                                    listOf("User", "System", "Disabled", "Hidden").forEach { kind ->
                                        DropdownMenuItem(
                                            text = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Checkbox(checked = kind in selectedTypes,
                                                        onCheckedChange = null)
                                                    Text(kind)
                                                }
                                            },
                                            onClick = {
                                                selectedTypes = if (kind in selectedTypes)
                                                    selectedTypes - kind else selectedTypes + kind
                                            },
                                        )
                                    }
                                    HorizontalDivider()
                                    Text("Access (any selected)", modifier = Modifier.padding(
                                        horizontal = 14.dp, vertical = 7.dp),
                                        style = MaterialTheme.typography.labelMedium)
                                    listOf("Shizuku", "Android granted", "Android revoked")
                                        .forEach { access ->
                                            DropdownMenuItem(
                                                text = {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Checkbox(checked = access in selectedAccess,
                                                            onCheckedChange = null)
                                                        Text(access)
                                                    }
                                                },
                                                onClick = {
                                                    selectedAccess = if (access in selectedAccess)
                                                        selectedAccess - access
                                                    else selectedAccess + access
                                                },
                                            )
                                        }
                                    DropdownMenuItem(
                                        text = { Text("All apps · clear filters") },
                                        onClick = {
                                            selectedTypes = emptySet()
                                            selectedAccess = emptySet()
                                            filtersOpen = false
                                        },
                                    )
                                }
                            }
                        }
                        Text(visible.size.toString() + " apps",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        IconButton(onClick = { descending = !descending },
                            modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Rounded.SwapVert, contentDescription =
                                if (descending) "Sort ascending" else "Sort descending")
                        }
                        IconButton(onClick = { refresh++ },
                            modifier = Modifier.size(36.dp),
                            enabled = !loading && changing == null && shizukuChanging == null) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "Refresh apps",
                                tint = DashboardTone.INFO.accent)
                        }
                    }
                    Text(numberOfClients.toString() + " Shizuku clients · " +
                        numberOfAndroidGrants.toString() + " Android grants",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!adb.connected) Text("Connect ADB to edit Android permissions",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (feedback != null) Text(feedback.orEmpty(),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall)
                    if (scanError != null) {
                        Text(scanError.orEmpty(), color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = { refresh++ }) { Text("Retry app discovery") }
                    }
                }
            }
        }
        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (!loading && scanError == null && visible.isEmpty()) item {
            Text(if (apps.isEmpty())
                "No Shizuku clients or supported Android developer permissions were found " +
                    "for the current user."
            else "No apps match the selected filters.")
        }
        if (scanError == null) {
            items(visible, key = { it.packageName + "#" + it.uid },
                contentType = { "app" }) { app ->
                var appExpanded by remember(app.packageName) { mutableStateOf(false) }
                var androidExpanded by remember(app.packageName) { mutableStateOf(false) }
                val grant = shizukuGrants[app.uid]
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { appExpanded = !appExpanded }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            InstalledAppIcon(app.packageName)
                            Column(Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text(app.label, style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(app.packageName +
                                    (if (app.system) " · System" else " · User") +
                                    (if (!app.enabled) " · Disabled" else ""),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            ModernStatusChip(
                                if (app.shizukuClient) when (grant) {
                                    true -> "Granted"
                                    false -> "Denied"
                                    null -> "Unknown"
                                } else app.grantedAdbPermissions.size.toString() + " grants",
                                if (app.shizukuClient) when (grant) {
                                    true -> DashboardTone.GOOD
                                    false -> DashboardTone.WARN
                                    null -> DashboardTone.NEUTRAL
                                } else DashboardTone.INFO,
                                icon = null,
                            )
                            Icon(if (appExpanded) Icons.Rounded.ExpandLess
                                else Icons.Rounded.ExpandMore,
                                contentDescription = if (appExpanded)
                                    "Collapse app details" else "Expand app details",
                                modifier = Modifier.size(20.dp))
                        }
                        if (appExpanded) {
                            HorizontalDivider()
                            Column(Modifier.fillMaxWidth().padding(
                                start = 12.dp, end = 12.dp, bottom = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (app.shizukuClient) {
                                    Row(Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Column(Modifier.weight(1f)) {
                                            Text(if (app.shizukuV3) "Shizuku API V3" else "Shizuku API",
                                                style = MaterialTheme.typography.bodyMedium)
                                            Text(when (grant) {
                                                true -> "Authorized by the local server"
                                                false -> "Not authorized"
                                                null -> "Server permission unavailable"
                                            }, style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        CompactPermissionSwitch(
                                            checked = grant == true,
                                            enabled = native.serviceReady && grant != null &&
                                                shizukuChanging == null,
                                            onCheckedChange = { wanted ->
                                                pendingShizuku = PendingShizukuGrant(app, wanted)
                                            },
                                        )
                                    }
                                }
                                if (app.declaredAdbPermissions.isNotEmpty()) {
                                    Row(Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        Text("Android permissions · " +
                                            app.grantedAdbPermissions.size + "/" +
                                            app.declaredAdbPermissions.size + " granted",
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.weight(1f))
                                        TextButton(onClick = { androidExpanded = !androidExpanded }) {
                                            Text(if (androidExpanded) "Hide" else "Show")
                                        }
                                    }
                                }
                                if (androidExpanded) for (permission in app.declaredAdbPermissions) {
                                    val granted = permission in app.grantedAdbPermissions
                                    val grantable = permission in app.grantableAdbPermissions
                                    val changingThis = changing == app.packageName + "#" + permission
                                    Row(Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Column(Modifier.weight(1f)) {
                                            Text(DashboardPresentation.permissionName(permission),
                                                style = MaterialTheme.typography.bodySmall)
                                            Text(when {
                                                changingThis -> "Verifying Android response…"
                                                !grantable -> "Not grantable with ADB"
                                                granted -> "Granted"
                                                else -> "Not granted"
                                            }, style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        CompactPermissionSwitch(checked = granted,
                                            enabled = adb.connected && grantable && changing == null,
                                            onCheckedChange = { wanted ->
                                                pending = PendingAndroidGrant(app, permission, wanted)
                                            })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingShizuku?.let { request ->
        AlertDialog(
            onDismissRequest = { pendingShizuku = null },
            title = { Text(if (request.grant)
                "Grant Shizuku API access?" else "Revoke Shizuku API access?") },
            text = { Text(
                "App: " + request.app.label + "\nPackage: " + request.app.packageName +
                "\n\nShizuku access runs the app's privileged requests as shell UID 2000. " +
                "Only grant this to apps you trust."
            ) },
            confirmButton = {
                Button(onClick = {
                    pendingShizuku = null
                    shizukuChanging = request.app.uid
                    scope.launch {
                        try {
                            val result = NativeShizukuManager.changeGrant(
                                context, request.app.uid, request.grant)
                            feedback = result.fold(
                                onSuccess = { "Local Shizuku server confirmed the change." },
                                onFailure = { "Shizuku grant failed: " +
                                    (it.localizedMessage ?: "unverified result") }
                            )
                            refresh++
                        } finally { shizukuChanging = null }
                    }
                }) { Text(if (request.grant) "Grant" else "Revoke") }
            },
            dismissButton = {
                TextButton(onClick = { pendingShizuku = null }) { Text("Cancel") }
            },
        )
    }

    pending?.let { request ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(if (request.grant) "Grant Android permission?" else "Revoke Android permission?") },
            text = {
                Text(
                    "App: " + request.app.label + "\n" +
                    "Package: " + request.app.packageName + "\n" +
                    "Permission: " + request.permission + "\n\n" +
                    (if (request.grant)
                        "This gives the selected app a privileged Android development permission. " +
                            "Only continue if you trust this app."
                    else "This may interrupt the app's features or running services.") +
                    "\n\nShizuku API authorization will not be changed."
                )
            },
            confirmButton = {
                Button(onClick = {
                    pending = null
                    val key = request.app.packageName + "#" + request.permission
                    changing = key
                    scope.launch {
                        try {
                            val result = AppAccessRepository.changeAndroidGrant(
                                context, request.app.packageName, request.permission, request.grant)
                            feedback = result.fold(
                                onSuccess = {
                                    "Android confirmed " + (if (request.grant) "grant" else "revoke") +
                                        ": " + request.app.label + " · " + request.permission.substringAfterLast('.')
                                },
                                onFailure = {
                                    "Android did not confirm the change: " +
                                        (it.localizedMessage ?: "permission change failed")
                                }
                            )
                            refresh++
                        } finally {
                            changing = null
                        }
                    }
                }) { Text(if (request.grant) "Grant" else "Revoke") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
    }
}

// Reuse decoded icons across scroll and expansion; no per-frame bitmap allocations.
private val appIconCache = LruCache<String, ImageBitmap>(48)

@Composable
private fun InstalledAppIcon(packageName: String) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = appIconCache.get(packageName), packageName) {
        value = appIconCache.get(packageName) ?: withContext(Dispatchers.Default) {
            runCatching {
                val drawable = context.packageManager.getApplicationIcon(packageName)
                val size = 64
                Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { result ->
                    val canvas = Canvas(result)
                    drawable.setBounds(0, 0, size, size)
                    drawable.draw(canvas)
                }.asImageBitmap()
            }.getOrNull()
        }?.also { appIconCache.put(packageName, it) }
    }
    if (bitmap != null) Image(bitmap = bitmap!!, contentDescription = null,
        modifier = Modifier.size(32.dp))
    else Icon(Icons.Rounded.Apps, contentDescription = null, modifier = Modifier.size(32.dp))
}


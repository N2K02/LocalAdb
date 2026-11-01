package dev.n2k02.localadb

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
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
    var category by remember { mutableStateOf("All") }
    var shizukuOnly by remember { mutableStateOf(false) }
    var grantFilter by remember { mutableStateOf<Boolean?>(null) }
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
            apps = AppAccessRepository.list(context)
            shizukuGrants = apps.filter { it.shizukuClient }.associate { app ->
                app.uid to NativeShizukuManager.granted(context, app.uid)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            scanError = "Android app discovery failed: " +
                (e.localizedMessage ?: "try refreshing or checking package visibility")
        } finally {
            loading = false
        }
    }

    val visible = remember(apps, search, category, shizukuOnly, grantFilter) {
        AppAccessPolicy.filter(apps, search, category, shizukuOnly, grantFilter)
    }
    val numberOfClients = apps.count { it.shizukuClient }
    val numberOfAndroidGrants = apps.sumOf { it.grantedAdbPermissions.size }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("App access", style = MaterialTheme.typography.titleMedium)
                        Text(numberOfClients.toString() + " Shizuku clients · " +
                            numberOfAndroidGrants + " Android grants",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { refresh++ }, enabled = !loading && changing == null) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null)
                        Text("Refresh")
                    }
                }

                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.Security, contentDescription = null)
                            Text("LocalAdb Shizuku manager",
                                style = MaterialTheme.typography.titleMedium)
                        }
                        Text(native.message, style = MaterialTheme.typography.bodyMedium)
                        Text(if (native.serviceReady)
                            "Third-party Shizuku clients can request access here. " +
                            "Grants are verified against the bundled server."
                        else "Connect Android ADB shell UID 2000 and start the embedded " +
                            "Shizuku server. No separate Shizuku application is required.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                NativeShizukuManager.start(context, explicit = true)
                            }, enabled = adb.connected && !native.starting && !native.serviceReady) {
                                Text(if (native.starting) "Starting" else "Start server")
                            }
                            OutlinedButton(onClick = {
                                NativeShizukuManager.refresh(context)
                                refresh++
                            }) { Text("Check status") }
                        }
                    }
                }

                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.VerifiedUser, contentDescription = null)
                            Text("Android developer permissions",
                                style = MaterialTheme.typography.titleMedium)
                        }
                        Text(if (adb.connected)
                            "ADB shell UID 2000 verified. Select an individual permission below."
                        else "Connect ADB first to change Android permissions.",
                            style = MaterialTheme.typography.bodyMedium)
                        Text("Only declared, ADB-grantable Android permissions are editable. " +
                            "Every change requires confirmation and Android readback.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text("Search apps or package name") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("All", "User", "System", "Disabled", "Hidden").forEach { kind ->
                        FilterChip(selected = category == kind, onClick = { category = kind },
                            label = { Text(kind) })
                    }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = shizukuOnly, onClick = { shizukuOnly = !shizukuOnly },
                        label = { Text("Shizuku clients") })
                    FilterChip(selected = grantFilter == null,
                        onClick = { grantFilter = null }, label = { Text("All Android") })
                    FilterChip(selected = grantFilter == true,
                        onClick = { grantFilter = true }, label = { Text("Granted") })
                    FilterChip(selected = grantFilter == false,
                        onClick = { grantFilter = false }, label = { Text("Not granted") })
                }
                Text(visible.size.toString() + " matching apps · current Android user",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (feedback != null) {
                    Text(feedback.orEmpty(), color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall)
                }
                if (scanError != null) {
                    Text(scanError.orEmpty(), color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = { refresh++ }) { Text("Retry app discovery") }
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
            items(visible, key = { it.packageName + "#" + it.uid }) { app ->
                var androidExpanded by remember(app.packageName, grantFilter) {
                    mutableStateOf(grantFilter != null)
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            InstalledAppIcon(app.packageName)
                            Column(Modifier.weight(1f)) {
                                Text(app.label, style = MaterialTheme.typography.titleMedium,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(app.packageName, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    AppCategoryLabel(if (app.system) "System" else "User")
                                    if (!app.enabled) AppCategoryLabel("Disabled")
                                    if (!app.hasLauncher) AppCategoryLabel("No launcher")
                                }
                            }
                        }
                        if (app.shizukuClient) {
                            HorizontalDivider()
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Rounded.Security, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary)
                                Column(Modifier.weight(1f)) {
                                    Text(if (app.shizukuV3) "Shizuku API V3 client"
                                         else "Potential legacy Shizuku client",
                                        style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        when (shizukuGrants[app.uid]) {
                                            true -> "Shizuku API: Granted by local server"
                                            false -> "Shizuku API: Not granted"
                                            null -> "Shizuku API: Unverified (server unavailable)"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Row(Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Allow Shizuku API",
                                    style = MaterialTheme.typography.bodyMedium)
                                Switch(
                                    checked = shizukuGrants[app.uid] == true,
                                    enabled = native.serviceReady &&
                                        shizukuGrants[app.uid] != null &&
                                        shizukuChanging == null,
                                    onCheckedChange = { wanted ->
                                        pendingShizuku = PendingShizukuGrant(app, wanted)
                                    },
                                )
                            }
                        }
                        if (app.declaredAdbPermissions.isNotEmpty()) {
                            HorizontalDivider()
                            Row(Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Android permissions · " +
                                        app.declaredAdbPermissions.size,
                                        style = MaterialTheme.typography.titleSmall)
                                    Text(app.grantedAdbPermissions.size.toString() + " granted",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
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
                                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Column(Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(permission.substringAfterLast('.').replace('_', ' '),
                                        style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        when {
                                            changingThis -> "Verifying Android's response…"
                                            !grantable -> "Not grantable via ADB on this Android version"
                                            granted -> "Granted by Android"
                                            else -> "Not granted by Android"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(
                                    checked = granted,
                                    enabled = adb.connected && grantable && changing == null,
                                    onCheckedChange = { wanted ->
                                        pending = PendingAndroidGrant(app, permission, wanted)
                                    },
                                )
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

@Composable
private fun InstalledAppIcon(packageName: String) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, packageName) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                val drawable = context.packageManager.getApplicationIcon(packageName)
                val size = 96
                Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { result ->
                    val canvas = Canvas(result)
                    drawable.setBounds(0, 0, size, size)
                    drawable.draw(canvas)
                }.asImageBitmap()
            }.getOrNull()
        }
    }
    if (bitmap != null) Image(bitmap = bitmap!!, contentDescription = null,
        modifier = Modifier.size(44.dp))
    else Icon(Icons.Rounded.Apps, contentDescription = null, modifier = Modifier.size(44.dp))
}

@Composable
private fun AppCategoryLabel(text: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

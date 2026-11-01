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

/**
 * Shizuku = compatibility only / authorization UNKNOWN / hand off to installed manager.
 * Android development permissions = verified PackageManager state / per-permission user action.
 */
@Composable
internal fun AppsPage(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val adb by AdbEngine.status.collectAsState()

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
    var manager by remember { mutableStateOf<String?>(null) }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(refresh) {
        loading = true
        scanError = null
        manager = withContext(Dispatchers.IO) {
            AppAccessRepository.installedManager(context)
        }
        try {
            apps = AppAccessRepository.list(context)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            scanError = "Android app discovery failed: " +
                (e.localizedMessage ?: "try refreshing or checking package visibility")
        } finally {
            loading = false
        }
    }

    fun openManager() {
        if (!AppAccessRepository.openShizukuManager(context)) {
            feedback = "Could not open the installed Shizuku manager. Check that it is enabled."
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
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.Security, contentDescription = null)
                            Text("Shizuku API access", style = MaterialTheme.typography.titleMedium)
                        }
                        Text("Authorization: managed by Shizuku (not readable here)",
                            style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "LocalAdb can find apps declaring Shizuku support, but only " +
                                "Shizuku's own manager can approve or revoke their API access.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall)
                        if (manager != null) {
                            Button(onClick = ::openManager) { Text("Open installed Shizuku manager") }
                        } else {
                            Text("Shizuku manager not installed or not enabled.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedButton(onClick = {
                                if (!AppAccessRepository.openProject(context))
                                    feedback = "No browser could open the Shizuku-Next project."
                            }) { Text("Shizuku-Next project") }
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
                                    Text("Shizuku access: unknown here · managed by Shizuku",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            TextButton(onClick = ::openManager, enabled = manager != null) {
                                Text("Manage in Shizuku")
                            }
                        }
                        if (app.declaredAdbPermissions.isNotEmpty()) {
                            HorizontalDivider()
                            Text("Android permissions (verified)",
                                style = MaterialTheme.typography.titleSmall)
                        }
                        for (permission in app.declaredAdbPermissions) {
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

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
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Shizuku eligibility and REAL Android ADB grants, deliberately distinct. */
@Composable
internal fun AppsPage(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var installed by remember { mutableStateOf<List<AppAccess>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var version by remember { mutableIntStateOf(0) }
    var search by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("All") }
    var shizukuOnly by remember { mutableStateOf(false) }
    var grantFilter by remember { mutableStateOf<Boolean?>(null) }
    var lastResult by remember { mutableStateOf("") }
    var changing by remember { mutableStateOf(false) }
    val adb by AdbEngine.status.collectAsState()
    LaunchedEffect(version) {
        loading = true
        installed = runCatching { AppAccessRepository.list(context) }.getOrDefault(emptyList())
        loading = false
    }
    val filtered = remember(installed, search, category, shizukuOnly, grantFilter) {
        AppAccessPolicy.filter(installed, search, category, shizukuOnly, grantFilter)
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("App access", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { version++ }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null)
                        Text("Refresh")
                    }
                }
                Text(
                    if (adb.connected) "Android ADB: verified shell UID 2000"
                    else "Android ADB: disconnected. Connect before changing Android grants.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.Security, contentDescription = null)
                            Text("Shizuku API authorization", style = MaterialTheme.typography.titleMedium)
                        }
                        Text("Shizuku-Next grants are manager-only. LocalAdb can detect compatible apps, but cannot forge or confirm the manager's authorization state.")
                        OutlinedButton(onClick = {
                            if (!AppAccessRepository.openShizukuManager(context))
                                AppAccessRepository.openProject(context)
                        }) { Text("Open Shizuku manager") }
                    }
                }
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text("Search apps or package name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("All", "User", "System", "Disabled").forEach { kind ->
                        FilterChip(selected = category == kind, onClick = { category = kind },
                            label = { Text(kind) })
                    }
                    FilterChip(selected = shizukuOnly, onClick = { shizukuOnly = !shizukuOnly },
                        label = { Text("Shizuku clients") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = grantFilter == null,
                        onClick = { grantFilter = null }, label = { Text("All grants") })
                    FilterChip(selected = grantFilter == true,
                        onClick = { grantFilter = true }, label = { Text("Android granted") })
                    FilterChip(selected = grantFilter == false,
                        onClick = { grantFilter = false }, label = { Text("Android revoked") })
                }
                Text("Showing " + filtered.size + " eligible apps",
                    style = MaterialTheme.typography.bodySmall)
                if (lastResult.isNotBlank()) {
                    Text(lastResult, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (!loading && filtered.isEmpty()) item {
            Text("No matching app declares a supported Android development permission or Shizuku API access.")
        }
        items(filtered, key = { it.packageName + "#" + it.uid }) { app ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        InstalledAppIcon(app.packageName)
                        Column(Modifier.weight(1f)) {
                            Text(app.label, style = MaterialTheme.typography.titleMedium)
                            Text(app.packageName, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                (if (app.system) "System" else "User") +
                                    (if (!app.enabled) " · Disabled" else ""),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                    if (app.shizukuClient) {
                        Text("Shizuku API compatible · authorization managed by Shizuku",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = {
                            if (!AppAccessRepository.openShizukuManager(context))
                                AppAccessRepository.openProject(context)
                        }) { Text("Manage Shizuku access") }
                    }
                    for (permission in app.declaredAdbPermissions) {
                        val granted = permission in app.grantedAdbPermissions
                        Row(Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(permission.substringAfterLast('.').replace('_', ' '),
                                    style = MaterialTheme.typography.bodyMedium)
                                Text(if (granted) "Android granted" else "Android not granted",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(
                                checked = granted,
                                enabled = adb.connected && !changing,
                                onCheckedChange = { requested ->
                                    changing = true
                                    scope.launch {
                                        val result = AppAccessRepository.changeAndroidGrant(
                                            context, app.packageName, permission, requested
                                        )
                                        lastResult = result.fold(
                                            onSuccess = { "Android verified permission change for " + app.label },
                                            onFailure = { it.message ?: "Android permission change failed" }
                                        )
                                        version++
                                        changing = false
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InstalledAppIcon(packageName: String) {
    val context = LocalContext.current
    val bitmap = remember(packageName) {
        runCatching {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            val size = 96
            val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            result.asImageBitmap()
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(44.dp))
    } else {
        Icon(Icons.Rounded.Apps, contentDescription = null, modifier = Modifier.size(44.dp))
    }
}

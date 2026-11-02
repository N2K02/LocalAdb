package dev.n2k02.localadb

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Match VRec's two-card ADB Settings submenu. Pairing/setup lives on Home. */
@Composable
internal fun AdbConnectionPage(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by AdbEngine.status.collectAsState()
    var autoPair by remember { mutableStateOf(AdbSettings.get(context, AdbSettings.Option.AUTO_PAIR)) }
    val enabledServices = remember {
        runCatching {
            Settings.Secure.getString(context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        }.getOrDefault("")
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Pairing & recovery", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                ToggleLine(AdbSettings.Option.AUTO_PAIR, "Auto-detect pairing code",
                    "Read the Android pairing dialog with Accessibility.",
                    onChanged = { autoPair = it })
                if (autoPair && !enabledServices.contains(AdbPairingAccessibilityService::class.java.name)) {
                    Text("Accessibility is needed for automatic pairing. Manual pairing is on Home.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                    }) { Text("Enable Accessibility") }
                }
                HorizontalDivider()
                ToggleLine(AdbSettings.Option.WATCHDOG, "Connection watchdog",
                    "Check connection health and recover automatically when possible.")
                ToggleLine(AdbSettings.Option.START_ON_BOOT, "Start on boot",
                    "Try to restore the helper after restarting and unlocking.")
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Connection & security", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                ToggleLine(AdbSettings.Option.RESTORE_AFTER_RESTART, "Restore after restart",
                    "Restore Wireless debugging after reboot, using saved pairing. " +
                        "Requires Android permission; never overrides a manual off setting.")
                ToggleLine(AdbSettings.Option.TCP_5555,
                    "Keep ADB without Wi-Fi (TCP/5555)",
                    "Keeps ADB available without Wi-Fi. TCP/5555 may be " +
                        "reachable by devices on your network.")
                if (state.connected && state.tcpConnected) {
                    OutlinedButton(onClick = { AdbEngine.disableTcp(context) },
                        modifier = Modifier.fillMaxWidth()) {
                        Text("Disable TCP/5555")
                    }
                }
            }
        }
    }
}

@Composable
private fun ToggleLine(option: AdbSettings.Option, title: String, description: String,
    onChanged: ((Boolean) -> Unit)? = null) {
    val context = LocalContext.current
    var checked by remember(option) { mutableStateOf(AdbSettings.get(context, option)) }
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = {
            checked = it
            AdbSettings.set(context, option, it)
            onChanged?.invoke(it)
            AdbEngine.preferencesChanged(context)
            if (option == AdbSettings.Option.TCP_5555 && !it) AdbEngine.disableTcp(context)
        })
    }
}

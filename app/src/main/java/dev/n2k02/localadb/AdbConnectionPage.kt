package dev.n2k02.localadb

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun AdbConnectionPage(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by AdbEngine.status.collectAsState()
    var code by remember { mutableStateOf("") }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Connection status", style = MaterialTheme.typography.titleMedium)
                Text(if (state.connected) "● Connected · Shell UID 2000"
                    else "○ " + state.phase.name.replace('_', ' '))
                Text(state.message, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { AdbEngine.start(context, explicit = true) }) {
                        Text(if (state.connected) "Check" else "Find / Retry")
                    }
                    OutlinedButton(onClick = { AdbEngine.openDeveloperOptions(context) }) {
                        Text("Developer options")
                    }
                }
                if (!state.connected) {
                    Text("Enable Wireless debugging in Developer options, then open Pair device with pairing code.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Pairing port: " + (state.pairingPort?.toString() ?: "Searching…") +
                         " · ADB port: " + (state.tlsPort?.toString() ?: "Searching…"),
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = code,
                        onValueChange = { incoming -> code = incoming.filter(Char::isDigit).take(6) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Six-digit Android pairing code") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation()
                    )
                    Button(
                        onClick = {
                            AdbEngine.submitPairingCode(context, code)
                            code = ""
                        }, enabled = code.length == 6 && state.pairingPort != null
                    ) { Text("Pair with this phone") }
                    Text("Or enable the Accessibility service below to read only Android's pairing dialog.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Pairing & recovery", style = MaterialTheme.typography.titleMedium)
                ToggleLine(
                    AdbSettings.Option.AUTO_PAIR, "Auto-detect pairing code",
                    "Read the Android pairing dialog with Accessibility."
                )
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }) { Text("Open Accessibility settings") }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                ToggleLine(
                    AdbSettings.Option.WATCHDOG, "Connection watchdog",
                    "Check connection health and recover automatically when possible."
                )
                ToggleLine(
                    AdbSettings.Option.START_ON_BOOT, "Start on boot",
                    "Try to reconnect after restarting and unlocking."
                )
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Connection & security", style = MaterialTheme.typography.titleMedium)
                ToggleLine(
                    AdbSettings.Option.RESTORE_AFTER_RESTART, "Restore after restart",
                    "After reboot, restore Wireless debugging using saved pairing only with Android permission. Never override a manual off setting."
                )
                ToggleLine(
                    AdbSettings.Option.TCP_5555, "Keep ADB without Wi-Fi (TCP/5555)",
                    "After verifying UID 2000, use TCP/5555 for reconnects. WARNING: may be reachable by other devices on your network."
                )
                OutlinedButton(
                    onClick = { AdbEngine.disableTcp(context) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Disable TCP/5555") }
            }
        }
    }
}

@Composable
private fun ToggleLine(option: AdbSettings.Option, title: String, description: String) {
    val context = LocalContext.current
    var checked by remember(option) { mutableStateOf(AdbSettings.get(context, option)) }
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = {
            checked = it
            AdbSettings.set(context, option, it)
            AdbEngine.preferencesChanged(context)
            if (option == AdbSettings.Option.TCP_5555 && !it) AdbEngine.disableTcp(context)
        })
    }
}

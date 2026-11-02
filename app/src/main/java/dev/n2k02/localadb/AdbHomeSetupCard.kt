package dev.n2k02.localadb

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Device setup stays available on Home while ADB connection Settings contains
 * only VRec's five preferences. ADB authentication always checks shell UID 2000.
 */
@Composable
internal fun AdbHomeSetupCard() {
    val context = LocalContext.current
    val state by AdbEngine.status.collectAsState()
    var code by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf(false) }
    if (state.connected) return

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Connect Android ADB", style = MaterialTheme.typography.titleMedium)
            Text(state.message, style = MaterialTheme.typography.bodySmall)
            Text("Enable Wireless debugging in Developer options and open Pair device with pairing code. After authorization, TCP/5555 can reconnect even without Wi-Fi.",
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { AdbEngine.start(context, explicit = true) }) {
                    Text("Find / Retry")
                }
                OutlinedButton(onClick = { AdbEngine.openDeveloperOptions(context) }) {
                    Text("Developer options")
                }
            }
            TextButton(onClick = { manual = !manual }) {
                Text(if (manual) "Hide pairing controls" else "Pair manually")
            }
            if (manual) {
                Text("Pairing port: " + (state.pairingPort?.toString() ?: "Searching…") +
                    " · ADB port: " + (state.tlsPort?.toString() ?: "Searching…"),
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = code,
                    onValueChange = { code = it.filter(Char::isDigit).take(6) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Six-digit Android pairing code") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation())
                Button(onClick = {
                    AdbEngine.submitPairingCode(context, code)
                    code = ""
                }, enabled = code.length == 6 && state.pairingPort != null) {
                    Text("Pair with this phone")
                }
                Text("You can also enable the pairing Accessibility helper in ADB connection settings.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

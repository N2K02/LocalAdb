package dev.n2k02.localadb

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Keeps Shizuku running: pairing expiry and the off-Wi-Fi USB debugging shield. */
@Composable
internal fun ResilienceCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var keepPaired by remember { mutableStateOf(Resilience.keepPaired(context)) }
    var shield by remember { mutableStateOf(Resilience.shield(context)) }
    val pairing by PairingTimeout.last.collectAsState()
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Keep Shizuku running", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold)
            Toggle("Keep pairing past 7 days",
                "Android forgets ADB pairings after 7 days; then Shizuku cannot start after a restart " +
                    "until you pair again. Also keeps any paired computer authorized. Turning it off " +
                    "restores Android's previous setting.", keepPaired) {
                keepPaired = it
                Resilience.setKeepPaired(context, it)
                scope.launch(Dispatchers.IO) { PairingTimeout.apply(context.applicationContext) }
            }
            Toggle("Survive Wi-Fi loss",
                "Keeps USB debugging on (no cable needed). Without it Android restarts ADB when Wi-Fi " +
                    "drops, which stops Shizuku and every app using it.", shield) {
                shield = it
                Resilience.setShield(context, it)
                if (it) scope.launch(Dispatchers.IO) { Resilience.onVerifiedShell(context.applicationContext) }
            }
            ResilienceText.pairing(keepPaired, pairing)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Toggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

internal object ResilienceText {
    fun pairing(keep: Boolean, last: PairingTimeout.Outcome?): String? = when (last) {
        PairingTimeout.Outcome.KEPT, PairingTimeout.Outcome.ALREADY_OFF -> if (keep) "Pairing will not expire." else null
        PairingTimeout.Outcome.GIVEN_BACK -> "Android's pairing expiry restored."
        PairingTimeout.Outcome.NOTHING_TO_GIVE_BACK -> if (keep) null else "Android's pairing expiry is in effect."
        PairingTimeout.Outcome.FAILED -> "Applied when ADB is next verified."
        null -> null
    }
}

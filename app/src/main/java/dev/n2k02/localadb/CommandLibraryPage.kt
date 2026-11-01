package dev.n2k02.localadb

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Standalone Commands submenu, not a second title bar.
 * Examples insert text into AdbConsolePage; they never execute automatically.
 */
@Composable
internal fun CommandLibraryPage(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onUse: (String) -> Unit,
) {
    val context = LocalContext.current
    val clipboard = remember(context) {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    var search by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("ALL") }
    val selected = CommandCategory.entries.firstOrNull { it.name == category }
    val entries = remember(search, category) { CommandLibrary.search(search, selected) }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ModernPanel(Modifier.fillMaxWidth(), DashboardTone.INFO) {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back to Adb console")
                    }
                    Text("Browse shell commands",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f))
                    ModernStatusChip(
                        CommandLibrary.entries.size.toString() + " examples",
                        DashboardTone.INFO, icon = Icons.Rounded.MenuBook
                    )
                }
                Text(
                    "Search a command, inspect what it does, then tap Use to fill the console. Nothing runs until you explicitly press Run.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        OutlinedTextField(
            value = search,
            onValueChange = { if (it.length <= 120) search = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            label = { Text("Search commands, descriptions or categories") },
            placeholder = { Text("e.g. battery, permissions, packages") }
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = category == "ALL", onClick = { category = "ALL" },
                label = { Text("All") })
            CommandCategory.entries.forEach { item ->
                FilterChip(selected = category == item.name,
                    onClick = { category = item.name }, label = { Text(item.label) })
            }
        }

        Row(Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(entries.size.toString() + " matching commands",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f))
            if (selected == CommandCategory.HOST) {
                ModernStatusChip("Reference only", DashboardTone.WARN, icon = null)
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            if (entries.isEmpty()) {
                item {
                    ModernPanel(Modifier.fillMaxWidth(), DashboardTone.NEUTRAL) {
                        Text("No matching commands. Try a different search or category.",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            items(entries, key = { it.command }) { entry ->
                val tone = when (entry.risk) {
                    CommandRisk.READ_ONLY -> DashboardTone.GOOD
                    CommandRisk.CHANGES_DEVICE -> DashboardTone.WARN
                    CommandRisk.HOST_ONLY -> DashboardTone.NEUTRAL
                }
                ModernPanel(Modifier.fillMaxWidth(), tone) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(entry.title, style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f))
                            ModernStatusChip(
                                when (entry.risk) {
                                    CommandRisk.READ_ONLY -> "Read only"
                                    CommandRisk.CHANGES_DEVICE -> "Modifies device"
                                    CommandRisk.HOST_ONLY -> "Host only"
                                }, tone, icon = null
                            )
                        }
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(entry.command,
                                modifier = Modifier.padding(11.dp),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis)
                        }
                        Text(entry.description, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (entry.hasPlaceholders && entry.usableInLocalAdb) {
                            Text("Edit <…> placeholders before running.",
                                style = MaterialTheme.typography.labelSmall,
                                color = DashboardTone.WARN.accent)
                        }
                        Row(Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            if (entry.usableInLocalAdb) {
                                Button(onClick = { onUse(entry.command) }) {
                                    Text("Use in console")
                                }
                            } else {
                                ModernStatusChip("Copy only · not runnable here",
                                    DashboardTone.WARN, icon = null)
                            }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = {
                                clipboard.setPrimaryClip(
                                    ClipData.newPlainText("Command", entry.command)
                                )
                            }) {
                                Icon(Icons.Rounded.ContentCopy, contentDescription = null,
                                    modifier = Modifier.size(17.dp))
                                Text("Copy")
                            }
                        }
                    }
                }
            }
        }
    }
}

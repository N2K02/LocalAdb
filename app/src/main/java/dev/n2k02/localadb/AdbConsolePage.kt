package dev.n2k02.localadb

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Original Compose UI inspired by the aShell You workflow, not a copy of its GPL code.
 * Executes commands solely with LocalAdb's existing authorized ADB shell UID 2000.
 */
@Composable
internal fun AdbConsolePage(modifier: Modifier = Modifier, onOpenConnection: () -> Unit) {
    val context = LocalContext.current
    val status by AdbEngine.status.collectAsState()
    val console by AdbConsole.state.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var picker by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmBookmark by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val filtered = remember(console.entries, search) {
        if (search.isBlank()) console.entries
        else console.entries.filter {
            it.command.contains(search, ignoreCase = true) ||
                it.output.contains(search, ignoreCase = true) ||
                it.error?.contains(search, ignoreCase = true) == true
        }
    }
    val clipboard = remember(context) {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    LaunchedEffect(Unit) { AdbConsole.loadBookmarks(context) }
    LaunchedEffect(filtered.size, search) {
        // Never scroll to an index removed by the search filter.
        if (filtered.isNotEmpty() && search.isBlank()) {
            list.animateScrollToItem(filtered.lastIndex)
        }
    }

    fun paste() {
        val clip = clipboard.primaryClip
        val value = if (clip != null && clip.itemCount > 0)
            clip.getItemAt(0).coerceToText(context)?.toString().orEmpty() else ""
        if (value.isNotBlank()) input = value.take(AdbConsoleProtocol.MAX_COMMAND_CHARS)
    }
    fun execute() {
        if (!status.connected) onOpenConnection()
        else AdbConsole.execute(input)
    }

    Column(
        modifier = modifier.fillMaxSize().imePadding().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { searchOpen = !searchOpen }) {
                Icon(Icons.Rounded.Search, contentDescription = "Search command output")
            }
            IconButton(onClick = { picker = "bookmarks" }) {
                Icon(Icons.Rounded.Bookmark, contentDescription = "Saved commands")
            }
            IconButton(onClick = { picker = "history" }) {
                Icon(Icons.Rounded.History, contentDescription = "Command history")
            }
            IconButton(onClick = { confirmClear = true }) {
                Icon(Icons.Rounded.ClearAll, contentDescription = "Clear output")
            }
            IconButton(onClick = { confirmBookmark = true }) {
                Icon(Icons.Rounded.BookmarkAdd, contentDescription = "Save current command")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.VerifiedUser, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp))
                Text(
                    if (status.connected) "Shell UID 2000 · Ready"
                    else "ADB disconnected",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onOpenConnection) { Text("Connection") }
        }

        if (searchOpen) {
            OutlinedTextField(
                value = search, onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Find in command output") },
            )
        }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { if (it.length <= AdbConsoleProtocol.MAX_COMMAND_CHARS) input = it },
                modifier = Modifier.weight(1f),
                label = { Text("Input ADB command") },
                placeholder = { Text("e.g. id; getprop ro.build.version.release") },
                minLines = 1, maxLines = 3,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { execute() }),
                trailingIcon = {
                    IconButton(onClick = {
                        if (console.running) AdbConsole.interrupt() else execute()
                    }, enabled = console.running || input.isNotBlank()) {
                        Icon(if (console.running) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                            contentDescription = if (console.running) "Stop command" else "Execute command")
                    }
                },
            )
            FilledTonalIconButton(onClick = { paste() }) {
                Icon(Icons.Rounded.ContentPaste, contentDescription = "Paste from clipboard")
            }
        }

        if (console.running) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("Executing through authorized ADB shell…",
                    style = MaterialTheme.typography.bodySmall)
                // Stop is already available in the command field's trailing icon.
            }
        }
        console.notice?.let {
            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider()

        if (filtered.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(if (console.entries.isEmpty()) "Run a command to see output here."
                    else "No output matches your search.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = list,
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                items(filtered) { entry ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Terminal, contentDescription = null,
                                    modifier = Modifier.size(18.dp))
                                Text("  " + entry.command, modifier = Modifier.weight(1f),
                                    fontFamily = FontFamily.Monospace, maxLines = 4,
                                    overflow = TextOverflow.Ellipsis)
                                IconButton(onClick = {
                                    clipboard.setPrimaryClip(
                                        ClipData.newPlainText("ADB command", entry.command))
                                }) { Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy command") }
                            }
                            HorizontalDivider()
                            Text(
                                entry.error ?: entry.output.ifEmpty { "(no output)" },
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (entry.error != null || (entry.exitCode ?: 0) != 0)
                                    MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurface,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Exit " + (entry.exitCode?.toString() ?: "unknown") +
                                        " · " + entry.durationMs + " ms" +
                                        (if (entry.truncated) " · truncated" else ""),
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = {
                                    clipboard.setPrimaryClip(ClipData.newPlainText(
                                        "ADB output", entry.error ?: entry.output))
                                }) { Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy output") }
                            }
                        }
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Shell UID 2000 can modify this device · output stays on device.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f))
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear command output?") },
            text = { Text("This clears displayed output; command history is separate.") },
            confirmButton = { Button(onClick = {
                AdbConsole.clearOutput()
                confirmClear = false
            }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
    if (confirmBookmark) {
        AlertDialog(
            onDismissRequest = { confirmBookmark = false },
            title = { Text("Save command as bookmark?") },
            text = { Text("Bookmarks are stored privately without encryption. Do not save passwords or tokens.") },
            confirmButton = { Button(onClick = {
                AdbConsole.toggleBookmark(context, input)
                confirmBookmark = false
            }, enabled = input.isNotBlank()) { Text("Save / Remove") } },
            dismissButton = { TextButton(onClick = { confirmBookmark = false }) { Text("Cancel") } },
        )
    }
    picker?.let { which ->
        AlertDialog(
            onDismissRequest = { picker = null },
            title = { Text(if (which == "history") "Session history" else "Saved commands") },
            text = {
                Column(
                    Modifier.fillMaxWidth().height(320.dp)
                        .horizontalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val commands = if (which == "history") console.history else console.bookmarks
                    if (commands.isEmpty()) Text("No commands yet.")
                    else commands.forEach { command ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable {
                                input = command
                                picker = null
                            },
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text(command, modifier = Modifier.padding(10.dp),
                                fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picker = null }) { Text("Close") } },
            dismissButton = {
                if (which == "history") TextButton(onClick = {
                    AdbConsole.clearHistory()
                    picker = null
                }) { Text("Clear history") }
            },
        )
    }
}

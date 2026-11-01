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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.SettingsEthernet
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Original Compose UI inspired by the aShell You workflow, not a copy of its GPL code.
 * Executes commands solely with LocalAdb's existing authorized ADB shell UID 2000.
 */
@Composable
internal fun AdbConsolePage(
    modifier: Modifier = Modifier,
    input: TextFieldValue,
    onInputChange: (TextFieldValue) -> Unit,
    onOpenConnection: () -> Unit,
    onOpenCommands: () -> Unit,
) {
    val context = LocalContext.current
    val status by AdbEngine.status.collectAsState()
    val console by AdbConsole.state.collectAsState()
    var suppressSuggestionsFor by remember { mutableStateOf<String?>(null) }
    var completionIndex by remember { mutableStateOf(CompletionIndex()) }
    var indexRefresh by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) indexRefresh++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(context, indexRefresh) {
        try {
            // Read only, never invokes ADB or Shizuku while a user is typing.
            completionIndex = AdbCompletionIndexLoader.load(context)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            completionIndex = CompletionIndex()
        }
    }

    var search by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var picker by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmBookmark by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    var expandedEntry by remember { mutableStateOf<AdbConsoleEntry?>(null) }
    // Always expand the newest result, including when the bounded
    // 35-entry history evicts its oldest entry and the count stays constant.
    LaunchedEffect(console.entries.lastOrNull()) {
        expandedEntry = console.entries.lastOrNull()
    }
    val completions = remember(input.text, input.selection, completionIndex,
        console.history, console.bookmarks, console.running, searchOpen,
        suppressSuggestionsFor) {
        if (console.running || searchOpen || !input.selection.collapsed ||
            suppressSuggestionsFor == input.text) emptyList()
        else AdbCommandCompletion.suggestions(
            input = input.text,
            cursor = input.selection.end,
            index = completionIndex,
            history = console.history,
            bookmarks = console.bookmarks,
        )
    }
    val filtered = remember(console.entries, search) {
        if (search.isBlank()) console.entries
        else console.entries.filter {
            it.command.contains(search, ignoreCase = true) ||
                it.output.contains(search, ignoreCase = true) ||
                it.error?.contains(search, ignoreCase = true) == true
        }
    }
    val newestFirst = remember(filtered) { filtered.asReversed() }

    val clipboard = remember(context) {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    LaunchedEffect(Unit) { AdbConsole.loadBookmarks(context) }
    LaunchedEffect(console.entries.lastOrNull(), search) {
        // Newest is index 0; reset scroll on a new output or search.
        if (filtered.isNotEmpty()) list.scrollToItem(0)
    }

    fun paste() {
        val clip = clipboard.primaryClip
        val value = if (clip != null && clip.itemCount > 0)
            clip.getItemAt(0).coerceToText(context)?.toString().orEmpty() else ""
        if (value.isNotBlank()) {
            val pasted = value.take(AdbConsoleProtocol.MAX_COMMAND_CHARS)
            onInputChange(TextFieldValue(pasted, selection = TextRange(pasted.length)))
            suppressSuggestionsFor = null
        }
    }
    fun execute() {
        if (!status.connected) onOpenConnection()
        else {
            suppressSuggestionsFor = input.text
            AdbConsole.execute(input.text)
        }
    }
    fun acceptCompletion(candidate: CommandCompletion) {
        val applied = AdbCommandCompletion.apply(input.text, candidate) ?: return
        onInputChange(TextFieldValue(applied.text, selection = TextRange(applied.cursor)))
        suppressSuggestionsFor = null
    }

    Column(
        modifier = modifier.fillMaxSize().imePadding().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (completions.isEmpty()) ModernPanel(
            Modifier.fillMaxWidth(),
            if (status.connected) DashboardTone.INFO else DashboardTone.WARN,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                ModernSectionHeader(
                    title = "Command console",
                    icon = Icons.Rounded.Terminal,
                    tone = DashboardTone.INFO,
                    subtitle = if (status.connected) "Authorized Android shell · UID 2000"
                        else "Connect to verified Android ADB",
                    status = if (status.connected) "Ready" else "Offline",
                    statusTone = if (status.connected) DashboardTone.GOOD
                        else DashboardTone.ERROR,
                )
                Row(Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    ModernStatusChip(
                        if (status.connected) {
                            if (status.tcpConnected) "TCP/5555" else "Wireless TLS"
                        } else "Disconnected",
                        if (status.connected) DashboardTone.GOOD else DashboardTone.WARN,
                        icon = Icons.Rounded.SettingsEthernet,
                    )
                    ModernStatusChip(console.entries.size.toString() + " results",
                        DashboardTone.INFO, icon = Icons.Rounded.History)
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = onOpenConnection) { Text("Connection") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = onOpenCommands) {
                        Icon(Icons.Rounded.MenuBook, contentDescription = null,
                            modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Commands")
                    }
                    IconButton(onClick = { searchOpen = !searchOpen }) {
                        Icon(Icons.Rounded.Search, contentDescription = "Search command output",
                            tint = DashboardTone.INFO.accent)
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
            }
        }

        if (searchOpen) {
            OutlinedTextField(
                value = search, onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Find in command output") },
            )
        }

        ModernPanel(Modifier.fillMaxWidth(), DashboardTone.INFO) {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Terminal, contentDescription = null,
                        tint = DashboardTone.INFO.accent, modifier = Modifier.size(19.dp))
                    Text("Run command", style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f))
                    ModernStatusChip(
                        if (console.running) "Running" else "Shell UID 2000",
                        if (console.running) DashboardTone.WARN
                        else if (status.connected) DashboardTone.GOOD
                        else DashboardTone.NEUTRAL,
                        icon = null
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { newValue ->
                            if (newValue.text.length <= AdbConsoleProtocol.MAX_COMMAND_CHARS) {
                                if (newValue.text != input.text) suppressSuggestionsFor = null
                                onInputChange(newValue)
                            }
                        },
                        modifier = Modifier.weight(1f).onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.Tab &&
                                completions.isNotEmpty()) {
                                acceptCompletion(completions.first())
                                true
                            } else false
                        },
                        label = { Text("Input ADB command") },
                        placeholder = { Text("e.g. id; getprop ro.build.version.release") },
                        minLines = 1, maxLines = 3,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            fontFamily = FontFamily.Monospace),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { execute() }),
                        trailingIcon = {
                            IconButton(onClick = {
                                if (console.running) AdbConsole.interrupt() else execute()
                            }, enabled = console.running || input.text.isNotBlank()) {
                                Icon(
                                    if (console.running) Icons.Rounded.Stop
                                    else Icons.Rounded.PlayArrow,
                                    contentDescription = if (console.running) "Stop command"
                                        else "Execute command",
                                    tint = if (console.running) DashboardTone.ERROR.accent
                                        else DashboardTone.GOOD.accent
                                )
                            }
                        },
                    )
                    FilledTonalIconButton(onClick = { paste() }) {
                        Icon(Icons.Rounded.ContentPaste,
                            contentDescription = "Paste from clipboard")
                    }
                }
                if (completions.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.AutoAwesome, contentDescription = null,
                            tint = DashboardTone.INFO.accent,
                            modifier = Modifier.size(18.dp))
                        Text("Autocomplete", style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f))
                        Text("Tap or Tab · never runs automatically",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 184.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        completions.forEach { candidate ->
                            Surface(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    acceptCompletion(candidate)
                                },
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(
                                        horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(9.dp)
                                ) {
                                    Icon(
                                        when (candidate.kind) {
                                            CompletionKind.PACKAGE -> Icons.Rounded.Inventory2
                                            CompletionKind.PERMISSION -> Icons.Rounded.Security
                                            CompletionKind.HISTORY -> Icons.Rounded.History
                                            CompletionKind.BOOKMARK -> Icons.Rounded.Bookmark
                                            CompletionKind.COMMAND -> Icons.Rounded.Terminal
                                        },
                                        contentDescription = null,
                                        tint = when (candidate.kind) {
                                            CompletionKind.PACKAGE -> DashboardTone.GOOD.accent
                                            CompletionKind.PERMISSION -> DashboardTone.WARN.accent
                                            else -> DashboardTone.INFO.accent
                                        },
                                        modifier = Modifier.size(17.dp)
                                    )
                                    Text(candidate.value, modifier = Modifier.weight(1f),
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall)
                                    Text(
                                        when (candidate.kind) {
                                            CompletionKind.PACKAGE -> "Package"
                                            CompletionKind.PERMISSION -> "Permission"
                                            CompletionKind.HISTORY -> "History"
                                            CompletionKind.BOOKMARK -> "Saved"
                                            CompletionKind.COMMAND -> "Command"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Text("Commands run through LocalAdb's verified shell only.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
            ModernStatusChip(it, DashboardTone.WARN, icon = null)
        }
        HorizontalDivider()

        if (filtered.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                ModernPanel(Modifier.fillMaxWidth(), DashboardTone.NEUTRAL) {
                    Column(Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Icon(Icons.Rounded.Terminal, contentDescription = null,
                            tint = DashboardTone.INFO.accent,
                            modifier = Modifier.size(36.dp))
                        Text(if (console.entries.isEmpty()) "Command output"
                            else "No matching output",
                            style = MaterialTheme.typography.titleMedium)
                        Text(if (console.entries.isEmpty())
                            "Run a command to see its results and exit status here."
                            else "Try another search term.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = list,
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                items(newestFirst) { entry ->
                    val expanded = expandedEntry === entry ||
                        (search.isNotBlank() && expandedEntry !in newestFirst &&
                            entry === newestFirst.firstOrNull())
                    val tone = when {
                        entry.error != null || (entry.exitCode != null && entry.exitCode != 0) ->
                            DashboardTone.ERROR
                        entry.exitCode == 0 -> DashboardTone.GOOD
                        else -> DashboardTone.WARN
                    }
                    ModernPanel(Modifier.fillMaxWidth(), tone) {
                        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                Icon(Icons.Rounded.Terminal, contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = DashboardTone.INFO.accent)
                                Column(
                                    modifier = Modifier.weight(1f).clickable {
                                        expandedEntry = if (expanded) null else entry
                                    },
                                    verticalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Text(entry.command,
                                        fontFamily = FontFamily.Monospace, maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyMedium)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        ModernStatusChip(
                                            when {
                                                entry.error != null -> "Failed"
                                                entry.exitCode == 0 -> "Exit 0"
                                                entry.exitCode != null -> "Exit " + entry.exitCode
                                                else -> "Unknown exit"
                                            }, tone, icon = null
                                        )
                                        Text(entry.durationMs.toString() + " ms" +
                                            (if (entry.truncated) " · truncated" else ""),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                IconButton(onClick = {
                                    clipboard.setPrimaryClip(ClipData.newPlainText(
                                        "ADB command", entry.command))
                                }) { Icon(Icons.Rounded.ContentCopy,
                                    contentDescription = "Copy command") }
                                IconButton(onClick = {
                                    expandedEntry = if (expanded) null else entry
                                }) {
                                    Icon(if (expanded) Icons.Rounded.ExpandLess
                                        else Icons.Rounded.ExpandMore,
                                        contentDescription = if (expanded) "Collapse output"
                                            else "Expand output")
                                }
                            }
                            if (expanded) {
                                HorizontalDivider()
                                Text(
                                    entry.error ?: entry.output.ifEmpty { "(no output)" },
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (entry.error != null ||
                                        (entry.exitCode != null && entry.exitCode != 0))
                                        MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurface
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
                                    }) {
                                        Icon(Icons.Rounded.ContentCopy,
                                            contentDescription = "Copy output")
                                    }
                                }
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
                AdbConsole.toggleBookmark(context, input.text)
                confirmBookmark = false
            }, enabled = input.text.isNotBlank()) { Text("Save / Remove") } },
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
                                onInputChange(TextFieldValue(
                                    command, selection = TextRange(command.length)))
                                suppressSuggestionsFor = command
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

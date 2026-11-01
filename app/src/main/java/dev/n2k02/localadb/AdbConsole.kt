package dev.n2k02.localadb

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class AdbConsoleEntry(
    val command: String,
    val output: String,
    val exitCode: Int?,
    val durationMs: Long,
    val error: String? = null,
    val truncated: Boolean = false,
)

internal data class AdbConsoleState(
    val entries: List<AdbConsoleEntry> = emptyList(),
    val history: List<String> = emptyList(),
    val bookmarks: List<String> = emptyList(),
    val running: Boolean = false,
    val notice: String? = null,
)

/** In-memory command output and history. Only explicit bookmarks persist privately. */
internal object AdbConsole {
    val state = MutableStateFlow(AdbConsoleState())
    private val lock = Any()
    private val history = AdbConsoleHistory()
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var current: Job? = null
    private const val PREFS = "adb_console_bookmarks"
    private const val KEY = "bookmarks"

    fun loadBookmarks(context: Context) {
        val stored = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY, emptySet()).orEmpty()
        state.update { it.copy(bookmarks = stored.sorted().take(AdbConsoleProtocol.MAX_BOOKMARKS)) }
    }

    fun toggleBookmark(context: Context, raw: String) {
        val command = runCatching { AdbConsoleProtocol.normalized(raw) }
            .getOrElse { problem ->
                state.update { s -> s.copy(notice = problem.message) }
                return
            }
        synchronized(lock) {
            val previous = state.value.bookmarks
            val newBookmarks = if (command in previous) previous.filterNot { it == command }
            else (previous + command).takeLast(AdbConsoleProtocol.MAX_BOOKMARKS).sorted()
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putStringSet(KEY, newBookmarks.toSet()).apply()
            state.update { it.copy(bookmarks = newBookmarks,
                notice = if (command in previous) "Bookmark removed" else "Bookmark saved on this device") }
        }
    }

    fun execute(input: String, timeoutMs: Long = AdbConsoleProtocol.DEFAULT_TIMEOUT_MS) {
        val command = runCatching { AdbConsoleProtocol.normalized(input) }.getOrElse { problem ->
            state.update { it.copy(notice = problem.message) }
            return
        }
        val shellCommand = runCatching {
            AdbConsoleProtocol.deviceShellCommand(command)
        }.getOrElse { problem ->
            state.update { it.copy(notice = problem.message) }
            return
        }
        if (timeoutMs !in 1000L..AdbConsoleProtocol.MAX_TIMEOUT_MS) {
            state.update { it.copy(notice = "Invalid command timeout") }
            return
        }
        synchronized(lock) {
            if (state.value.running || current?.isActive == true) {
                state.update { it.copy(notice = "A command is already running") }
                return
            }
            if (scope.coroutineContext[Job]?.isActive != true) {
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            }
            history.add(command)
            state.update { it.copy(running = true, history = history.snapshot(), notice = null) }
            current = scope.launch {
                val started = SystemClock.elapsedRealtime()
                try {
                    val response = AdbTransport.execVerifiedUserCommand(shellCommand, timeoutMs)
                    val elapsed = SystemClock.elapsedRealtime() - started
                    val entry = response.fold(
                        onSuccess = { info -> AdbConsoleEntry(
                            command = command, output = info.output, exitCode = info.exitCode,
                            durationMs = elapsed, truncated = info.truncated
                        ) },
                        onFailure = { err -> AdbConsoleEntry(
                            command, "", null, elapsed,
                            error = err.localizedMessage ?: "Command execution failed"
                        ) }
                    )
                    state.update { s ->
                        s.copy(
                            running = false,
                            entries = (s.entries + entry).takeLast(AdbConsoleProtocol.MAX_ENTRIES),
                            notice = if (entry.error != null) "Command failed" else null
                        )
                    }
                } catch (cancelled: CancellationException) {
                    state.update { it.copy(running = false, notice = "Command interrupted") }
                    throw cancelled
                } catch (failure: Exception) {
                    val elapsed = SystemClock.elapsedRealtime() - started
                    state.update { s -> s.copy(
                        running = false,
                        entries = (s.entries + AdbConsoleEntry(
                            command, "", null, elapsed, failure.localizedMessage ?: "Execution failed"
                        )).takeLast(AdbConsoleProtocol.MAX_ENTRIES)
                    ) }
                }
            }
        }
    }

    fun interrupt() {
        synchronized(lock) {
            if (!state.value.running) return
            current?.cancel()
            current = null
            AdbTransport.interruptUserShell()
            state.update { it.copy(running = false, notice = "Command interrupted; reconnect if necessary") }
        }
    }

    fun clearOutput() = state.update { it.copy(entries = emptyList(), notice = "Output cleared") }
    fun clearHistory() {
        synchronized(lock) {
            history.clear()
            state.update { it.copy(history = emptyList(), notice = "Session history cleared") }
        }
    }

    fun shutdown() {
        interrupt()
        synchronized(lock) {
            current?.cancel()
            current = null
            scope.cancel()
            history.clear()
            state.value = AdbConsoleState()
        }
    }
}

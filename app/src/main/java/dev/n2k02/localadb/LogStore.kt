package dev.n2k02.localadb

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.ArrayDeque

/**
 * Opt-in bounded journal, kept in memory only. Scrub common secrets before recording or sharing.
 * Consumers must never write raw ADB command output or credentials to this journal.
 */
internal class LogStore(private val capacity: Int = 1000) {
    init { require(capacity > 0) }
    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private val _enabled = MutableStateFlow(false)
    private val _revision = MutableStateFlow(0L)
    val enabled: StateFlow<Boolean> = _enabled
    val revision: StateFlow<Long> = _revision

    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        if (enabled) append("Logs enabled")
    }

    fun append(message: String) {
        if (!_enabled.value) return
        val entry = LogSanitizer.redact(message).replace('\n', ' ').replace('\r', ' ').take(600)
        synchronized(lock) {
            if (lines.size == capacity) lines.removeFirst()
            lines.addLast(entry)
            _revision.value++
        }
    }

    fun snapshot(): List<String> = synchronized(lock) { lines.toList() }

    fun clear() {
        synchronized(lock) { lines.clear(); _revision.value++ }
    }

    fun shutdown() {
        _enabled.value = false
        clear()
    }
}

internal object LogSanitizer {
    fun redact(value: String): String = value
        .replace(Regex("(?i)\\b(bearer\\s+)[^\\s]+"), "$1[REDACTED]")
        .replace(Regex("(?i)\\b(token|password|api[_-]?key|secret|authorization)(\\s*[:=]\\s*)[^\\s&,;]+"), "$1$2[REDACTED]")
        .replace(Regex("(?i)https?://[^\\s]+"), "[URL]")
        .replace(Regex("(?i)[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}"), "[EMAIL]")
}
internal val appLog = LogStore()

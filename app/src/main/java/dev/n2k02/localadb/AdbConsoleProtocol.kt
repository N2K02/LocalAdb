package dev.n2k02.localadb

/** Validated commands execute only inside the authenticated Android ADB shell. */
internal object AdbConsoleProtocol {
    const val MAX_COMMAND_CHARS = 4_096
    const val MAX_CAPTURE_CHARS = 128_000
    const val MAX_ENTRIES = 35
    const val MAX_HISTORY = 60
    const val MAX_BOOKMARKS = 40
    const val DEFAULT_TIMEOUT_MS = 30_000L
    const val MAX_TIMEOUT_MS = 120_000L

    fun normalized(input: String): String {
        val value = input.trim()
        require(value.isNotBlank()) { "Enter an ADB shell command" }
        require(value.length <= MAX_COMMAND_CHARS) { "Command is too long" }
        require(value.none { it == '\u0000' || it == '\u0008' || it == '\u007f' }) {
            "Command contains unsupported control characters"
        }
        return value.replace("\r\n", "\n").replace('\r', '\n').replace('\n', ';')
    }

    /**
     * This app is an ADB *shell*, not an adb host. A familiar "adb shell"
     * prefix is allowed; other host-only ADB verbs fail with a clear error.
     * No ADB command is run merely by completing or selecting text.
     */
    fun deviceShellCommand(normalized: String): String {
        val trimmed = normalized.trim()
        if (!Regex("^adb(?=\\s|$)", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)) {
            return trimmed
        }
        val prefix = Regex("^adb\\s+shell(?=\\s|$)", RegexOption.IGNORE_CASE)
            .find(trimmed)
        require(prefix != null) {
            "LocalAdb is already an Android shell. Use 'adb shell <command>' or " +
                "enter the on-device shell command directly; host-only adb verbs are unavailable."
        }
        val actual = trimmed.substring(prefix.value.length).trimStart()
        require(actual.isNotBlank()) {
            "Enter a command after 'adb shell', e.g. adb shell id."
        }
        return actual
    }

    fun wrap(command: String, marker: String): String {
        require(marker.matches(Regex("__localadb_result_[a-z0-9]{12}__")))
        val dollar = '$'
        // Subshell: exit inside the user's command must not exit the long-lived shell.
        return "( " + normalized(command) + " ); _localadb_code=" + dollar +
            "?; printf '\\n" + marker + ":%s\\n' \"" + dollar + "_localadb_code\""
    }

    data class Parsed(val output: String, val exitCode: Int, val truncated: Boolean)

    fun parse(result: String, marker: String): Parsed {
        val suffix = "\n" + marker + ":"
        val idx = result.lastIndexOf(suffix)
        require(idx >= 0) { "The shell did not return a completion marker" }
        val code = result.substring(idx + suffix.length).trim().toIntOrNull()
        require(code != null && code in 0..255) { "Invalid shell exit code" }
        var output = result.substring(0, idx).trimEnd('\n', '\r')
        val truncated = output.contains("[LocalAdb output truncated]")
        if (output.length > MAX_CAPTURE_CHARS) {
            output = output.take(MAX_CAPTURE_CHARS) + "\n[LocalAdb output truncated]"
        }
        return Parsed(output, code, truncated || result.length > MAX_CAPTURE_CHARS)
    }
}

internal class AdbConsoleHistory(private val limit: Int = AdbConsoleProtocol.MAX_HISTORY) {
    init { require(limit >= 1) }
    private val items = ArrayDeque<String>()

    fun add(command: String) {
        val normalized = AdbConsoleProtocol.normalized(command)
        items.remove(normalized)
        items.addFirst(normalized)
        if (items.size > limit) items.removeLast()
    }

    fun snapshot(): List<String> = items.toList()
    fun clear() = items.clear()
}

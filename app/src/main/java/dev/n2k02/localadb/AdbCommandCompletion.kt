package dev.n2k02.localadb

/**
 * Pure, local shell completion engine. Suggestions never execute anything.
 * They are deliberately Android SHELL commands, not host-side "adb" verbs:
 * LocalAdb already owns an authenticated UID 2000 shell session.
 */
internal enum class CompletionKind { COMMAND, PACKAGE, PERMISSION, HISTORY, BOOKMARK }

internal data class CompletionIndex(
    val packages: List<String> = emptyList(),
    val systemPackages: Set<String> = emptySet(),
    val permissions: List<String> = emptyList(),
    val declaredPermissions: Map<String, List<String>> = emptyMap(),
)

internal data class CommandCompletion(
    val value: String,
    val kind: CompletionKind,
    val replaceFrom: Int,
    val replaceTo: Int,
    val original: String,
    val detail: String = "",
    val appendSpace: Boolean = false,
)

internal data class AppliedCompletion(val text: String, val cursor: Int)

/** State-independent engine, comprehensively unit-tested without a device. */
internal object AdbCommandCompletion {
    const val MAX_SUGGESTIONS = 6
    private val words = Regex("\\S+")
    private val packageCommands = setOf(
        "clear", "path", "enable", "disable", "disable-user", "uninstall",
        "suspend", "unsuspend", "hide", "unhide", "compile"
    )
    private val grantCommands = setOf("grant", "revoke")

    // Common on-device commands and useful command prefixes; no arguments
    // are executed by choosing a suggestion. Names intentionally authored
    // for LocalAdb (do not import aShell You's GPL command catalogue).
    val commandCatalog: List<String> = listOf(
        "id", "id -u", "whoami", "pwd", "ls", "ls -la", "cd", "cat",
        "stat", "du -h", "df -h", "free -h", "ps -A", "top -n 1",
        "uptime", "date", "uname -a", "env", "which", "echo",
        "getprop", "getprop ro.build.version.release",
        "getprop ro.build.version.sdk", "getprop ro.product.model",
        "getprop ro.build.fingerprint", "setprop",
        "pm list packages", "pm list packages -3", "pm list packages -s",
        "pm list permissions", "pm path", "pm clear", "pm grant",
        "pm revoke", "pm enable", "pm disable-user",
        "pm uninstall", "pm dump", "pm resolve-activity",
        "cmd package list packages", "cmd package resolve-activity",
        "cmd package grant", "cmd package revoke",
        "dumpsys", "dumpsys battery", "dumpsys activity",
        "dumpsys package", "dumpsys window", "dumpsys wifi",
        "dumpsys power", "dumpsys notification", "dumpsys meminfo",
        "dumpsys cpuinfo", "dumpsys connectivity",
        "settings list system", "settings list secure",
        "settings list global", "settings get secure",
        "settings get global", "settings get system",
        "settings put secure", "settings put global",
        "am force-stop", "am start", "am broadcast", "am kill",
        "appops get", "appops set",
        "wm size", "wm density", "input keyevent", "input tap",
        "input swipe", "input text",
        "svc wifi", "svc data", "logcat -d", "logcat -c",
        "logcat -v time", "pidof", "getenforce"
    )

    // Names are NOT grants. They are autocomplete candidates, combined with
    // requested permissions obtained from the device's own PackageManager.
    val commonPermissions: List<String> = listOf(
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_BACKGROUND_LOCATION",
        "android.permission.ACCESS_LOCAL_NETWORK",
        "android.permission.CAMERA",
        "android.permission.DUMP",
        "android.permission.NEARBY_WIFI_DEVICES",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.READ_CONTACTS",
        "android.permission.READ_LOGS",
        "android.permission.READ_PHONE_STATE",
        "android.permission.READ_SMS",
        "android.permission.RECORD_AUDIO",
        "android.permission.WRITE_SECURE_SETTINGS"
    )

    private enum class ContextKind { COMMAND, PACKAGE, PERMISSION }

    fun suggestions(
        input: String,
        cursor: Int,
        index: CompletionIndex = CompletionIndex(),
        history: List<String> = emptyList(),
        bookmarks: List<String> = emptyList(),
        limit: Int = MAX_SUGGESTIONS,
    ): List<CommandCompletion> {
        if (input.isBlank() || cursor !in 0..input.length || limit <= 0 ||
            input.length > AdbConsoleProtocol.MAX_COMMAND_CHARS) return emptyList()

        val lastSeparator = input.substring(0, cursor)
            .indexOfLast { it == ';' || it == '\n' || it == '|' || it == '&' }
        val rawStart = lastSeparator + 1
        val raw = input.substring(rawStart, cursor)
        val indent = raw.indexOfFirst { !it.isWhitespace() }
        if (indent < 0) return emptyList()
        val segmentStart = rawStart + indent
        val left = input.substring(segmentStart, cursor)
        val tokens = words.findAll(left).toList()
        if (tokens.isEmpty()) return emptyList()
        // The current input is already a shell. "adb ..." is a host command,
        // so never imply that executing it inside LocalAdb's shell will work.
        if (tokens.first().value.equals("adb", ignoreCase = true)) return emptyList()
        val atWhitespace = left.lastOrNull()?.isWhitespace() == true
        val wordIndex = if (atWhitespace) tokens.size else tokens.lastIndex
        val tokenStart = if (atWhitespace) cursor
            else segmentStart + tokens.last().range.first
        val token = if (atWhitespace) "" else tokens.last().value
        val tokenEnd = if (atWhitespace) cursor
            else cursor + input.substring(cursor).takeWhile {
                !it.isWhitespace() && it != ';' && it != '|' && it != '&'
            }.length
        val normalized = tokens.map { it.value.lowercase(java.util.Locale.ROOT) }
        val mode = context(normalized, wordIndex, token)
        val results = ArrayList<CommandCompletion>()
        val seen = HashSet<String>()
        val max = limit.coerceAtMost(MAX_SUGGESTIONS)

        fun add(value: String, kind: CompletionKind, from: Int, to: Int,
                detail: String = "", appendSpace: Boolean = false) {
            if (value.isBlank() || results.size >= max) return
            if (value.equals(input.substring(from, to), ignoreCase = true)) return
            // Don't propose an identical no-op completion; a prefix may be an
            // entire command but still have a later argument being edited.
            if (input.substring(from, to) == value) return
            if (!seen.add(value.lowercase(java.util.Locale.ROOT))) return
            results += CommandCompletion(value, kind, from, to,
                input.substring(from, to), detail, appendSpace)
        }

        when (mode) {
            ContextKind.PACKAGE -> {
                if (token.contains('/') || token.contains('\'') || token.contains('"')) return emptyList()
                index.packages.asSequence()
                    .filter { it.startsWith(token, ignoreCase = true) }
                    .sortedWith(String.CASE_INSENSITIVE_ORDER)
                    .take(max).forEach { name ->
                        val requiresPermission = isGrantContext(normalized)
                        add(name, CompletionKind.PACKAGE, tokenStart, tokenEnd,
                            if (name in index.systemPackages) "System app" else "Installed app",
                            appendSpace = requiresPermission && tokenEnd == input.length)
                    }
            }
            ContextKind.PERMISSION -> {
                if (token.contains('/') || token.contains('\'') || token.contains('"')) return emptyList()
                val target = packageArgument(normalized)
                val declared = index.declaredPermissions[target]
                val candidates = if (!declared.isNullOrEmpty()) declared
                    else index.permissions + commonPermissions
                candidates.asSequence().distinct().filter {
                    it.startsWith(token, ignoreCase = true) ||
                        it.substringAfterLast('.').startsWith(token, ignoreCase = true)
                }.sortedWith(String.CASE_INSENSITIVE_ORDER).take(max)
                    .forEach { add(it, CompletionKind.PERMISSION, tokenStart, tokenEnd,
                        if (declared != null) "Declared by target app" else "Android permission") }
            }
            ContextKind.COMMAND -> {
                if (left.isBlank()) return emptyList()
                // Saved and recent commands are local to this device. Suggestions
                // are only displayed after the user types a prefix.
                val prefix = left.trimEnd()
                bookmarks.asSequence().filter { it.startsWith(prefix, ignoreCase = true) }
                    .take(max).forEach { add(it, CompletionKind.BOOKMARK, segmentStart, tokenEnd,
                        "Saved command") }
                history.asSequence().filter { it.startsWith(prefix, ignoreCase = true) }
                    .take(max).forEach { add(it, CompletionKind.HISTORY, segmentStart, tokenEnd,
                        "Recent command") }
                commandCatalog.asSequence().filter { it.startsWith(prefix, ignoreCase = true) }
                    .take(max).forEach { add(it, CompletionKind.COMMAND, segmentStart, tokenEnd,
                        "Shell command") }
            }
        }
        return results
    }

    private fun context(tokens: List<String>, position: Int, token: String): ContextKind {
        val first = tokens.firstOrNull().orEmpty()
        val second = tokens.getOrNull(1).orEmpty()
        val third = tokens.getOrNull(2).orEmpty()
        if (first == "pm") {
            if (second in grantCommands) return when (position) {
                2 -> ContextKind.PACKAGE
                3 -> ContextKind.PERMISSION
                else -> ContextKind.COMMAND
            }
            if (second in packageCommands && position == 2) return ContextKind.PACKAGE
        }
        if (first == "cmd" && second == "package") {
            if (third in grantCommands) return when (position) {
                3 -> ContextKind.PACKAGE
                4 -> ContextKind.PERMISSION
                else -> ContextKind.COMMAND
            }
            if (third in packageCommands && position == 3) return ContextKind.PACKAGE
        }
        if (first == "am" && second in setOf("force-stop", "kill") && position == 2) {
            return ContextKind.PACKAGE
        }
        if (first == "dumpsys" && second == "package" && position == 2) {
            return ContextKind.PACKAGE
        }
        if (first == "appops" && second in setOf("get", "set", "reset") &&
            position == 2) return ContextKind.PACKAGE
        if (first == "monkey" && tokens.getOrNull(position - 1) == "-p") {
            return ContextKind.PACKAGE
        }
        // Dotted package prefixes are supported in known package-oriented
        // commands, not in unrelated paths or getprop property names.
        if (token.startsWith("android.permission.", ignoreCase = true)) {
            return ContextKind.PERMISSION
        }
        if (token.contains('.') && !token.contains('/') &&
            first in setOf("pm", "cmd", "am", "appops", "monkey") &&
            token.matches(Regex("[A-Za-z][A-Za-z0-9_.]*"))) return ContextKind.PACKAGE
        return ContextKind.COMMAND
    }

    private fun isGrantContext(tokens: List<String>) =
        (tokens.getOrNull(0) == "pm" && tokens.getOrNull(1) in grantCommands) ||
            (tokens.getOrNull(0) == "cmd" && tokens.getOrNull(1) == "package" &&
                tokens.getOrNull(2) in grantCommands)

    private fun packageArgument(tokens: List<String>): String =
        if (tokens.firstOrNull() == "pm") tokens.getOrNull(2).orEmpty()
        else tokens.getOrNull(3).orEmpty()

    /** Refuses stale replacements or anything longer than the command limit. */
    fun apply(input: String, completion: CommandCompletion): AppliedCompletion? {
        val from = completion.replaceFrom
        val to = completion.replaceTo
        if (from !in 0..input.length || to !in from..input.length ||
            input.substring(from, to) != completion.original) return null
        val suffix = input.substring(to)
        val spacing = if (completion.appendSpace && (suffix.isEmpty() ||
            !suffix.first().isWhitespace())) " " else ""
        val output = input.substring(0, from) + completion.value + spacing + suffix
        if (output.length > AdbConsoleProtocol.MAX_COMMAND_CHARS) return null
        return AppliedCompletion(output, from + completion.value.length + spacing.length)
    }
}

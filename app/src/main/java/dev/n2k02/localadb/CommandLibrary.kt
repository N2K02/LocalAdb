package dev.n2k02.localadb

/** Built-in examples authored for LocalAdb. No GPL command list is copied. */
internal enum class CommandCategory(val label: String) {
    DEVICE("Device"), APPS("Apps"), PERMISSIONS("Permissions"),
    SYSTEM("System"), LOGS("Logs"), NETWORK("Network"),
    INPUT("Input"), HOST("ADB host reference")
}
internal enum class CommandRisk { READ_ONLY, CHANGES_DEVICE, HOST_ONLY }

internal data class CommandExample(
    val title: String,
    val command: String,
    val description: String,
    val category: CommandCategory,
    val risk: CommandRisk = CommandRisk.READ_ONLY,
) {
    val usableInLocalAdb: Boolean get() = risk != CommandRisk.HOST_ONLY
    val hasPlaceholders: Boolean get() = command.contains('<') || command.contains('>')
}

/**
 * Every command marked usable executes in an Android shell, not on a desktop
 * ADB host. Examples with placeholders are templates to edit before running.
 * Host-side ADB commands are documentation-only (copy permitted, Use disabled).
 */
internal object CommandLibrary {
    private fun e(title: String, command: String, description: String,
                  category: CommandCategory, risk: CommandRisk = CommandRisk.READ_ONLY) =
        CommandExample(title, command, description, category, risk)

    val entries: List<CommandExample> = listOf(
        e("Shell identity", "id", "Show current UID, GID and shell groups.", CommandCategory.DEVICE),
        e("Verify shell UID", "id -u", "Confirm the Android shell identity (usually 2000).", CommandCategory.DEVICE),
        e("Android version", "getprop ro.build.version.release", "Read the Android release version.", CommandCategory.DEVICE),
        e("Android API level", "getprop ro.build.version.sdk", "Read the SDK/API level.", CommandCategory.DEVICE),
        e("Device model", "getprop ro.product.model", "Read the device model.", CommandCategory.DEVICE),
        e("Device fingerprint", "getprop ro.build.fingerprint", "Read the current firmware build identifier.", CommandCategory.DEVICE),
        e("Boot time", "uptime", "Show how long Android has been running.", CommandCategory.DEVICE),
        e("Kernel information", "uname -a", "Display Linux kernel and architecture.", CommandCategory.DEVICE),
        e("Battery status", "dumpsys battery", "Read battery level, charging and health data.", CommandCategory.DEVICE),
        e("Storage overview", "df -h", "Show mounted volume capacity and free space.", CommandCategory.DEVICE),

        e("Installed apps", "pm list packages", "List all installed package names.", CommandCategory.APPS),
        e("User-installed apps", "pm list packages -3", "List user-installed packages.", CommandCategory.APPS),
        e("System packages", "pm list packages -s", "List system packages.", CommandCategory.APPS),
        e("Locate an APK", "pm path <package>", "Display the installed APK path for a package.", CommandCategory.APPS),
        e("Inspect a package", "dumpsys package <package>", "Inspect an app's components and permissions.", CommandCategory.APPS),
        e("Find an app process", "pidof <package>", "Show process IDs associated with an app.", CommandCategory.APPS),
        e("Stop an app", "am force-stop <package>", "Force-stop an app until it restarts.", CommandCategory.APPS, CommandRisk.CHANGES_DEVICE),
        e("Enable a package", "pm enable <package>", "Enable a disabled app component or package.", CommandCategory.APPS, CommandRisk.CHANGES_DEVICE),
        e("Disable an app for user 0", "pm disable-user --user 0 <package>", "Disable an app for the primary Android user.", CommandCategory.APPS, CommandRisk.CHANGES_DEVICE),
        e("Clear app data", "pm clear <package>", "Delete an app's local data. Irreversible.", CommandCategory.APPS, CommandRisk.CHANGES_DEVICE),

        e("List permissions", "pm list permissions", "List Android permission names.", CommandCategory.PERMISSIONS),
        e("AppOps state", "appops get <package>", "Review app operations for the target package.", CommandCategory.PERMISSIONS),
        e("Grant an eligible permission", "pm grant <package> <permission>", "Grant a declared, ADB-grantable Android permission.", CommandCategory.PERMISSIONS, CommandRisk.CHANGES_DEVICE),
        e("Revoke a permission", "pm revoke <package> <permission>", "Revoke an eligible Android permission from a package.", CommandCategory.PERMISSIONS, CommandRisk.CHANGES_DEVICE),
        e("Change AppOps mode", "appops set <package> <operation> <mode>", "Modify a package's app operation policy.", CommandCategory.PERMISSIONS, CommandRisk.CHANGES_DEVICE),
        e("Secure settings list", "settings list secure", "List secure Android settings and values.", CommandCategory.PERMISSIONS),
        e("Secure setting lookup", "settings get secure <key>", "Read a particular secure setting.", CommandCategory.PERMISSIONS),
        e("Global setting lookup", "settings get global <key>", "Read a particular global setting.", CommandCategory.PERMISSIONS),

        e("Running processes", "ps -A", "List running processes and PIDs.", CommandCategory.SYSTEM),
        e("CPU overview", "top -n 1", "Capture a process CPU activity snapshot.", CommandCategory.SYSTEM),
        e("Memory overview", "dumpsys meminfo", "Inspect system and process memory.", CommandCategory.SYSTEM),
        e("Power state", "dumpsys power", "Inspect power management details.", CommandCategory.SYSTEM),
        e("Windows state", "dumpsys window", "Read window manager state.", CommandCategory.SYSTEM),
        e("Activity state", "dumpsys activity", "Read Android activity manager state.", CommandCategory.SYSTEM),
        e("System time", "date", "Read current device clock.", CommandCategory.SYSTEM),
        e("Property list", "getprop", "Read Android system properties.", CommandCategory.SYSTEM),
        e("SELinux mode", "getenforce", "Show whether SELinux is enforcing.", CommandCategory.SYSTEM),
        e("Device density", "wm density", "Read the active display density.", CommandCategory.SYSTEM),
        e("Display resolution", "wm size", "Read display dimensions.", CommandCategory.SYSTEM),

        e("Recent system logs", "logcat -d -t 200", "Display the most recent logcat messages.", CommandCategory.LOGS),
        e("App-specific logs", "logcat -d -s LocalAdbShizuku", "Show native Shizuku startup diagnostics.", CommandCategory.LOGS),
        e("Crash buffer", "logcat -d -b crash", "Read available Android crash reports.", CommandCategory.LOGS),
        e("CPU diagnostics", "dumpsys cpuinfo", "Inspect per-process CPU load.", CommandCategory.LOGS),
        e("Notification service", "dumpsys notification", "Inspect Android notification services.", CommandCategory.LOGS),

        e("Wi-Fi status", "dumpsys wifi", "Inspect Android Wi-Fi service state.", CommandCategory.NETWORK),
        e("Connections status", "dumpsys connectivity", "Inspect active networking and transport.", CommandCategory.NETWORK),
        e("Network interfaces", "ip addr show", "Display device network interface addresses.", CommandCategory.NETWORK),
        e("Routing table", "ip route", "Inspect current IP routing.", CommandCategory.NETWORK),
        e("Socket connections", "ss -tun", "List open TCP and UDP connections.", CommandCategory.NETWORK),

        e("Press Back", "input keyevent KEYCODE_BACK", "Send an Android Back key event.", CommandCategory.INPUT, CommandRisk.CHANGES_DEVICE),
        e("Press Home", "input keyevent KEYCODE_HOME", "Return to Android's home screen.", CommandCategory.INPUT, CommandRisk.CHANGES_DEVICE),
        e("Tap screen", "input tap <x> <y>", "Simulate a tap at specified display coordinates.", CommandCategory.INPUT, CommandRisk.CHANGES_DEVICE),
        e("Swipe screen", "input swipe <x1> <y1> <x2> <y2> <ms>", "Simulate a swipe gesture.", CommandCategory.INPUT, CommandRisk.CHANGES_DEVICE),
        e("Type text", "input text <text>", "Send text to the active Android input target.", CommandCategory.INPUT, CommandRisk.CHANGES_DEVICE),

        e("List ADB devices (host)", "adb devices -l", "Runs on a computer/ADB host, not inside LocalAdb's shell.", CommandCategory.HOST, CommandRisk.HOST_ONLY),
        e("Connect over TCP (host)", "adb connect <host>:5555", "Host command for connecting to a TCP-enabled ADB daemon.", CommandCategory.HOST, CommandRisk.HOST_ONLY),
        e("Pair over wireless (host)", "adb pair <host>:<port> <code>", "Host command for pairing with Wireless debugging.", CommandCategory.HOST, CommandRisk.HOST_ONLY),
        e("Switch to USB (host)", "adb usb", "Host-side ADB transport command.", CommandCategory.HOST, CommandRisk.HOST_ONLY),
        e("Enter a shell (host)", "adb shell", "This is a host command; LocalAdb already runs an authenticated shell.", CommandCategory.HOST, CommandRisk.HOST_ONLY),
        e("Pull file (host)", "adb pull <remote> <local>", "Copies from Android to a host computer; not runnable in the on-device shell.", CommandCategory.HOST, CommandRisk.HOST_ONLY)
    )

    init { require(entries.map { it.command }.distinct().size == entries.size) }

    fun search(query: String, category: CommandCategory? = null): List<CommandExample> {
        val words = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return entries.filter { entry ->
            (category == null || entry.category == category) &&
                words.all { word ->
                    listOf(entry.title, entry.command, entry.description, entry.category.label)
                        .any { it.contains(word, ignoreCase = true) }
                }
        }
    }
}

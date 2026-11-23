package dev.n2k02.localadb

/**
 * Restarting adbd (tcpip:5555, usb:) makes init kill every process in adbd's
 * cgroup, including the Shizuku server LocalAdb started over ADB and every
 * Shizuku client's helpers (e.g. VREC's recorder). Shell cannot leave that
 * cgroup on Android 17 (cgroup.procs is system:system; measured by JemRec).
 * A Retry on a session that is already verified has nothing to reconnect and
 * would only re-run the TLS->TCP promotion, so it is skipped while Shizuku runs.
 */
internal object AdbdRestartGuard {
    fun mayRetry(sessionVerified: Boolean, shizukuServing: Boolean): Boolean =
        !(sessionVerified && shizukuServing)
}

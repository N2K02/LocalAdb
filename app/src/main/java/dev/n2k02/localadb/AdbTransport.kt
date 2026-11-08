package dev.n2k02.localadb

import android.content.Context
import dev.n2k02.adb.core.AdbTransportMode
import kotlinx.coroutines.CancellationException

/** Backward-compatible call sites; never creates a second libadb manager.
 * Privileged commands, pairing, TLS/TCP switching, UID checks and stream
 * interruption are all handled by n2k02AdbCoreLibrary. */
internal object AdbTransport {
    const val LOOPBACK = "127.0.0.1"
    const val LEGACY_TCP_PORT = 5555

    val lastKnownConnected: Boolean
        get() = SharedAdbHostAdapter.current()?.state?.value?.connected == true

    val isConnected: Boolean get() = lastKnownConnected

    private fun shared() = checkNotNull(SharedAdbHostAdapter.current()) {
        "Shared ADB core is not started; no legacy transport fallback is permitted"
    }

    suspend fun exec(command: String, timeoutMs: Long = 15_000): Result<String> =
        try {
            Result.success(shared().execVerified(command, timeoutMs))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }

    /** Retain LocalAdb's explicit command exit-code protocol on the shared wire. */
    internal suspend fun execVerifiedUserCommand(
        command: String,
        timeoutMs: Long = AdbConsoleProtocol.DEFAULT_TIMEOUT_MS,
    ): Result<AdbConsoleProtocol.Parsed> =
        try {
            require(timeoutMs in 1000L..AdbConsoleProtocol.MAX_TIMEOUT_MS)
            val validated = AdbConsoleProtocol.normalized(command)
            val marker = "__localadb_result_" +
                java.util.UUID.randomUUID().toString().replace("-", "").take(12) + "__"
            val response = shared().execVerified(AdbConsoleProtocol.wrap(validated, marker), timeoutMs)
            Result.success(AdbConsoleProtocol.parse(response, marker))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }

    /** Cancel the owning console Job; the shared wire retires blocked streams. */
    internal fun interruptUserShell() = Unit

    suspend fun pairLocal(context: Context, port: Int, code: String): Result<Unit> =
        try {
            check(shared().pair(port, code)) { "ADB pairing failed" }
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }

    suspend fun connectLocalTls(context: Context, port: Int): Result<Unit> =
        try {
            check(shared().connectTls(port)) { "ADB TLS connection failed" }
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }

    suspend fun connect(context: Context, host: String, port: Int): Result<Unit> =
        try {
            require(host == LOOPBACK && port == LEGACY_TCP_PORT)
            val adb = shared()
            check(adb.retry()) { "ADB TCP connection failed" }
            check(adb.state.value.transport == AdbTransportMode.LOCAL_TCP_5555) {
                "TCP/5555 is not the verified ADB transport"
            }
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }

    suspend fun switchToTcpip(port: Int = LEGACY_TCP_PORT): Result<Unit> =
        try {
            require(port == LEGACY_TCP_PORT)
            val adb = shared()
            adb.setOption(dev.n2k02.adb.core.AdbOption.TCP_5555, true)
            check(adb.retry()) { "ADB TCP/5555 promotion failed" }
            check(adb.state.value.transport == AdbTransportMode.LOCAL_TCP_5555) {
                "ADB TCP/5555 is not verified"
            }
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }

    suspend fun switchToUsb(): Result<Unit> = try {
        check(shared().disableTcp()) { "TCP/5555 shutdown failed" }
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }

    suspend fun close() {
        SharedAdbHostAdapter.current()?.stop()
    }

    suspend fun disconnect() = close()
}

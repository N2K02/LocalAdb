package dev.n2k02.localadb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Tests the user-service path, independently of the manager Binder's readiness. */
internal object LocalHelperProbe {
    data class Reply(val uid: Int, val nonce: String)
    interface Connection {
        suspend fun connect()
        suspend fun ping(nonce: String): Reply
        suspend fun close()
    }

    suspend fun run(connection: Connection, nonce: String, timeoutMs: Long = 15_000): String {
        var cancellation: CancellationException? = null
        try {
            return withTimeoutOrNull(timeoutMs) {
                connection.connect()
                val reply = connection.ping(nonce)
                check(reply.uid == 2000) { "Helper returned UID ${reply.uid}; expected 2000" }
                check(reply.nonce == nonce) { "Helper response did not match this test" }
                "LocalHelper connected · UID 2000 · response verified"
            } ?: "LocalHelper failed: no connection or response within ${timeoutMs / 1000}s"
        } catch (cancelled: CancellationException) {
            cancellation = cancelled
            throw cancelled
        } catch (failure: Exception) {
            return "LocalHelper failed: ${failure.message ?: failure.javaClass.simpleName}"
        } finally {
            // Stop the dedicated test service even after timeout, navigation or Quit.
            try {
                withContext(NonCancellable) { connection.close() }
            } catch (failure: Exception) {
                if (cancellation != null) cancellation.addSuppressed(failure) else throw failure
            }
        }
    }
}

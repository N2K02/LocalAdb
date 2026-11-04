package dev.n2k02.localadb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalHelperProbeTest {
    private class FakeConnection : LocalHelperProbe.Connection {
        var uid = 2000
        var echo = true
        var waitForConnection = false
        var waitForResponse = false
        var connectFailure: Exception? = null
        var pingFailure: Exception? = null
        var closeFailure: Exception? = null
        var connections = 0
        var pings = 0
        var closes = 0
        override suspend fun connect() {
            connections++
            connectFailure?.let { throw it }
            if (waitForConnection) awaitCancellation()
        }
        override suspend fun ping(nonce: String): LocalHelperProbe.Reply {
            pings++
            pingFailure?.let { throw it }
            if (waitForResponse) awaitCancellation()
            return LocalHelperProbe.Reply(uid, if (echo) nonce else "old response")
        }
        override suspend fun close() {
            closes++
            closeFailure?.let { throw it }
        }
    }

    @Test fun successfulConnectionRequiresShellUidAndMatchingResponse() = runTest {
        val connection = FakeConnection()
        assertEquals("LocalHelper connected · UID 2000 · response verified",
            LocalHelperProbe.run(connection, "fresh nonce"))
        assertEquals(1, connection.connections)
        assertEquals(1, connection.pings)
        assertEquals(1, connection.closes)
    }

    @Test fun serverConnectionWithoutHelperCallbackTimesOutAndCleansUp() = runTest {
        val connection = FakeConnection().apply { waitForConnection = true }
        assertTrue(LocalHelperProbe.run(connection, "nonce").contains("within 15s"))
        assertEquals(0, connection.pings)
        assertEquals(1, connection.closes)
    }

    @Test fun helperCallbackWithoutAnIpcResponseIsNotSuccess() = runTest {
        val connection = FakeConnection().apply { waitForResponse = true }
        assertTrue(LocalHelperProbe.run(connection, "nonce").startsWith("LocalHelper failed:"))
        assertEquals(1, connection.pings)
        assertEquals(1, connection.closes)
    }

    @Test fun appUidOrRootUidMustNotPassTheShellTest() = runTest {
        for (uid in listOf(0, 10000, -1)) {
            val connection = FakeConnection().apply { this.uid = uid }
            assertTrue(LocalHelperProbe.run(connection, "nonce").contains("expected 2000"))
            assertEquals(1, connection.closes)
        }
    }

    @Test fun staleResponseDoesNotPass() = runTest {
        val connection = FakeConnection().apply { echo = false }
        assertTrue(LocalHelperProbe.run(connection, "nonce").contains("did not match"))
        assertEquals(1, connection.closes)
    }

    @Test fun bindFailureIsReportedWithoutCallingTheHelper() = runTest {
        val connection = FakeConnection().apply {
            connectFailure = SecurityException("permission denied")
        }
        assertTrue(LocalHelperProbe.run(connection, "nonce").contains("permission denied"))
        assertEquals(0, connection.pings)
        assertEquals(1, connection.closes)
    }

    @Test fun binderDeathDuringIpcIsReportedAndCleanedUp() = runTest {
        val connection = FakeConnection().apply {
            pingFailure = IllegalStateException("helper disconnected")
        }
        assertTrue(LocalHelperProbe.run(connection, "nonce").contains("helper disconnected"))
        assertEquals(1, connection.closes)
    }

    @Test fun cancellingOnQuitCleansUpWithoutReportingSuccess() = runTest {
        val connection = FakeConnection().apply { waitForConnection = true }
        var completed = false
        val job = launch {
            LocalHelperProbe.run(connection, "nonce")
            completed = true
        }
        runCurrent()
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(completed)
        assertEquals(1, connection.closes)
    }

    @Test fun retryUsesANewConnectionAfterFailure() = runTest {
        val failed = FakeConnection().apply { waitForConnection = true }
        assertTrue(LocalHelperProbe.run(failed, "first").startsWith("LocalHelper failed:"))
        val recovered = FakeConnection()
        assertTrue(LocalHelperProbe.run(recovered, "second").startsWith("LocalHelper connected"))
        assertEquals(1, failed.closes)
        assertEquals(1, recovered.closes)
    }

    @Test fun parentTimeoutIsCancellationAndStillCleansUp() = runTest {
        val connection = FakeConnection().apply { waitForConnection = true }
        var cancelled = false
        try {
            withTimeout(1) { LocalHelperProbe.run(connection, "nonce") }
        } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertEquals(1, connection.closes)
    }

    @Test fun cleanupFailureCannotTurnQuitCancellationIntoACompletedTest() = runTest {
        val connection = FakeConnection().apply {
            waitForConnection = true
            closeFailure = IllegalStateException("server stopped")
        }
        val job = launch { LocalHelperProbe.run(connection, "nonce") }
        runCurrent()
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, connection.closes)
    }

    @Test fun cleanupFailureMustNotReportAPassedTest() = runTest {
        val connection = FakeConnection().apply {
            closeFailure = IllegalStateException("helper could not stop")
        }
        var failed = false
        try {
            LocalHelperProbe.run(connection, "nonce")
        } catch (_: IllegalStateException) { failed = true }
        assertTrue(failed)
        assertEquals(1, connection.closes)
    }
}

package dev.n2k02.localadb

import org.junit.Assert.*
import org.junit.Test

class AdbConsoleProtocolTest {
    private val marker = "__localadb_result_123abc456def__"

    @Test fun validCommandIsNormalized() {
        assertEquals("id;getprop ro.build.version.sdk",
            AdbConsoleProtocol.normalized("  id\ngetprop ro.build.version.sdk  "))
    }

    @Test fun emptyAndControlCharacterCommandsAreRejected() {
        for (invalid in listOf("", "   ", "id\u0000whoami", "id\u007f")) {
            assertThrows(IllegalArgumentException::class.java) {
                AdbConsoleProtocol.normalized(invalid)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            AdbConsoleProtocol.normalized("a".repeat(AdbConsoleProtocol.MAX_COMMAND_CHARS + 1))
        }
    }

    @Test fun shellWrapperDoesNotExitPersistentSession() {
        val wrapped = AdbConsoleProtocol.wrap("exit 7", marker)
        assertTrue(wrapped.startsWith("( exit 7 );"))
        assertTrue(wrapped.contains("_localadb_code=" + '$' + "?"))
        assertTrue(wrapped.contains(marker))
        assertThrows(IllegalArgumentException::class.java) {
            AdbConsoleProtocol.wrap("id", "unsafe;echo")
        }
    }

    @Test fun outputAndExitCodeAreParsedWithoutFakeSuccess() {
        val result = AdbConsoleProtocol.parse(
            "uid=2000(shell) gid=2000(shell)\n" + marker + ":0", marker)
        assertEquals("uid=2000(shell) gid=2000(shell)", result.output)
        assertEquals(0, result.exitCode)
        assertFalse(result.truncated)
        val failure = AdbConsoleProtocol.parse("\n" + marker + ":127", marker)
        assertEquals("", failure.output)
        assertEquals(127, failure.exitCode)
    }

    @Test fun missingOrTamperedCompletionIsNeverSuccess() {
        for (invalid in listOf(
            "output without marker", "\n" + marker + ":invalid",
            "\n" + marker + ":256", "\n" + marker + ":-1"
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                AdbConsoleProtocol.parse(invalid, marker)
            }
        }
    }

    @Test fun outputHasHardCaptureLimit() {
        val large = "x".repeat(AdbConsoleProtocol.MAX_CAPTURE_CHARS + 30) +
            "\n" + marker + ":0"
        val parsed = AdbConsoleProtocol.parse(large, marker)
        assertTrue(parsed.truncated)
        assertTrue(parsed.output.length <= AdbConsoleProtocol.MAX_CAPTURE_CHARS + 32)
    }

    @Test fun sessionHistoryIsBoundedUniqueAndNotPersisted() {
        val history = AdbConsoleHistory(limit = 2)
        history.add("id")
        history.add("getprop")
        history.add("id")
        assertEquals(listOf("id", "getprop"), history.snapshot())
        history.add("whoami")
        assertEquals(listOf("whoami", "id"), history.snapshot())
        history.clear()
        assertTrue(history.snapshot().isEmpty())
    }

    @Test fun transportExecutesConsoleWithVerifiedSharedCoreAndExitMarker() {
        val source = java.io.File("src/main/java/dev/n2k02/localadb/AdbTransport.kt").readText()
        assertTrue(source.contains("shared().execVerified(AdbConsoleProtocol.wrap(validated, marker), timeoutMs)"))
        assertTrue(source.contains("AdbConsoleProtocol.parse(response, marker)"))
        assertTrue(source.contains("catch (cancelled: CancellationException)"))
        assertTrue(source.contains("no legacy transport fallback"))
        assertFalse(source.contains("AbsAdbConnectionManager"))
        assertTrue(source.contains("fun interruptUserShell()"))
    }
}

package dev.n2k02.localadb

import org.junit.Assert.*
import org.junit.Test

class LogStoreTest {
    @Test fun disabledByDefault() {
        val store = LogStore(2)
        store.append("not saved")
        assertTrue(store.snapshot().isEmpty())
    }
    @Test fun ringBufferDropsOldest() {
        val store = LogStore(2)
        store.setEnabled(true)
        store.clear()
        store.append("one")
        store.append("two")
        store.append("three")
        assertEquals(listOf("two", "three"), store.snapshot())
    }
    @Test fun disablesAndClearsOnShutdown() {
        val store = LogStore()
        store.setEnabled(true)
        store.append("secret")
        store.shutdown()
        assertFalse(store.enabled.value)
        assertTrue(store.snapshot().isEmpty())
        store.append("after shutdown")
        assertTrue(store.snapshot().isEmpty())
    }
    @Test fun redactsCredentials() {
        val input = "Authorization: abc123 Bearer xYzToken password=hunter2 api_key=abcdef "
        val result = LogSanitizer.redact(input)
        assertFalse(result.contains("abc123"))
        assertFalse(result.contains("xYzToken"))
        assertFalse(result.contains("hunter2"))
        assertFalse(result.contains("abcdef"))
    }
    @Test fun redactsUrlsAndEmail() {
        val result = LogSanitizer.redact("https://example.com/q=a someone@example.com")
        assertEquals("[URL] [EMAIL]", result)
    }
    @Test fun rejectZeroCapacity() {
        assertThrows(IllegalArgumentException::class.java) { LogStore(0) }
    }
}

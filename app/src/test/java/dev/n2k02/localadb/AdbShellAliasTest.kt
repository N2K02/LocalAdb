package dev.n2k02.localadb

import org.junit.Assert.*
import org.junit.Test

class AdbShellAliasTest {
    @Test fun shellPrefixRunsTheInnerShellCommandOnly() {
        assertEquals("id", AdbConsoleProtocol.deviceShellCommand("adb shell id"))
        assertEquals("pm list packages",
            AdbConsoleProtocol.deviceShellCommand("ADB SHELL pm list packages"))
        assertEquals("id", AdbConsoleProtocol.deviceShellCommand("id"))
        assertEquals("pm grant com.example android.permission.DUMP",
            AdbConsoleProtocol.deviceShellCommand(
                "adb shell pm grant com.example android.permission.DUMP"))
    }

    @Test fun adbHostOnlyAndEmptyShellFormsFailBeforeExecution() {
        for (invalid in listOf("adb", "adb devices", "adb connect 127.0.0.1:5555",
            "adb pull /sdcard/file /tmp/file", "adb shell", "adb shell   ")) {
            assertThrows("Should refuse unsupported $invalid",
                IllegalArgumentException::class.java) {
                AdbConsoleProtocol.deviceShellCommand(invalid)
            }
        }
    }

    @Test fun executeValidatesShellAliasBeforeCallingAdb() {
        val code = java.io.File("src/main/java/dev/n2k02/localadb/AdbConsole.kt").readText()
        assertTrue(code.contains("AdbConsoleProtocol.deviceShellCommand(command)"))
        assertTrue(code.contains("AdbTransport.execVerifiedUserCommand(shellCommand, timeoutMs)"))
        assertFalse(code.contains("AdbTransport.execVerifiedUserCommand(command, timeoutMs)"))
    }
}

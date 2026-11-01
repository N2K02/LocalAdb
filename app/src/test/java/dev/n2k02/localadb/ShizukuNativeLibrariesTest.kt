package dev.n2k02.localadb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuNativeLibrariesTest {
    @Test fun arm64LibraryMatchesNativeStarterSystemProperty() {
        val arm64 = ShizukuNativeLibraries.select(listOf("arm64-v8a", "armeabi-v7a"))
        assertEquals("lib/arm64-v8a/libshizuku.so", arm64.starterEntry)
        assertEquals("lib/arm64-v8a/librish.so", arm64.rishEntry)
        assertEquals("/data/local/tmp/dev.n2k02.localadb-shizuku/lib/arm64/librish.so",
            arm64.shellRishPath("/data/local/tmp/dev.n2k02.localadb-shizuku"))
    }

    @Test fun otherNativeStarterArchitecturesMatch() {
        val expectations = listOf(
            "armeabi-v7a" to "arm",
            "x86_64" to "x86_64",
            "x86" to "x86",
        )
        expectations.forEach { (apkAbi, serverAbi) ->
            val resolved = ShizukuNativeLibraries.select(listOf(apkAbi))
            assertEquals("lib/" + apkAbi + "/librish.so", resolved.rishEntry)
            assertEquals("/data/local/tmp/shizuku/lib/" + serverAbi + "/librish.so",
                resolved.shellRishPath("/data/local/tmp/shizuku"))
        }
    }

    @Test fun unsupportedAbisFailRatherThanLaunchingABrokenServer() {
        assertThrows(IllegalStateException::class.java) {
            ShizukuNativeLibraries.select(emptyList())
        }
        assertThrows(IllegalStateException::class.java) {
            ShizukuNativeLibraries.select(listOf("mips64"))
        }
    }

    @Test fun firstActuallySupportedAbiWins() {
        assertEquals("x86_64", ShizukuNativeLibraries.select(
            listOf("mips64", "x86_64", "x86")).apkAbi)
    }

    @Test fun serverStagesRishBeforeForkingAppProcess() {
        val s = java.io.File(
            "src/main/java/dev/n2k02/localadb/NativeShizukuManager.kt").readText()
        assertTrue(s.indexOf("localadb_rish_ready") <
            s.indexOf("Launching Shizuku-Next native starter"))
        assertTrue(s.contains("native.shellRishPath(dir)"))
        assertTrue(s.contains("native.serverAbi"))
        assertTrue(s.contains("rishUri"))
        assertTrue(s.contains(".embedded-shizuku/rish"))
        assertTrue(s.contains("content read --uri"))
        assertTrue(s.contains("Shizuku child process crashed"))
    }

    @Test fun nativeLibrariesAreVerifiedAndNeverExposedToNonShellApps() {
        val s = java.io.File(
            "src/main/java/dev/n2k02/localadb/ShizukuPayloadProvider.kt").readText()
        assertTrue(s.contains("Binder.getCallingUid() != Process.SHELL_UID"))
        assertTrue(s.contains("ShizukuNativeLibraries.select"))
        assertTrue(s.contains("libraries.rishEntry"))
        assertTrue(s.contains("EXPECTED_SHA256"))
        assertTrue(s.contains("MAX_NATIVE_BYTES"))
        assertTrue(s.contains("elf.contentEquals"))
        assertTrue(s.contains("MODE_READ_ONLY"))
    }
}

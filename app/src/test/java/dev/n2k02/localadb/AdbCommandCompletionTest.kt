package dev.n2k02.localadb

import org.junit.Assert.*
import org.junit.Test

class AdbCommandCompletionTest {
    private val index = CompletionIndex(
        packages = listOf("com.example.app", "com.example.tools",
            "com.google.android.gms", "org.sample.client"),
        systemPackages = setOf("com.google.android.gms"),
        permissions = listOf("android.permission.CAMERA",
            "android.permission.DUMP", "android.permission.WRITE_SECURE_SETTINGS"),
        declaredPermissions = mapOf(
            "com.example.app" to listOf("android.permission.CAMERA",
                "android.permission.WRITE_SECURE_SETTINGS"),
            "com.example.tools" to listOf("android.permission.DUMP")
        )
    )

    private fun suggest(text: String, cursor: Int = text.length,
                        history: List<String> = emptyList(),
                        bookmarks: List<String> = emptyList()) =
        AdbCommandCompletion.suggestions(text, cursor, index, history, bookmarks)

    @Test fun shellCommandPrefixCompletesMultipleWords() {
        val result = suggest("pm gr")
        assertTrue(result.any { it.value == "pm grant" && it.kind == CompletionKind.COMMAND })
        assertTrue(suggest("getprop ro.build.version.r")
            .any { it.value == "getprop ro.build.version.release" })
    }

    @Test fun packageNamesAreReadOnlyDynamicAndPrefixFiltered() {
        val list = suggest("pm grant com.ex")
        assertEquals(listOf("com.example.app", "com.example.tools"),
            list.map { it.value })
        assertTrue(list.all { it.kind == CompletionKind.PACKAGE })
        assertEquals("System app", suggest("pm path com.google").first().detail)
        assertTrue(suggest("pm path org.unknown").isEmpty())
    }

    @Test fun packageSuggestionsWorkWithAmDumpsysCmdAndAppops() {
        for (line in listOf("am force-stop com.ex", "dumpsys package com.ex",
            "cmd package grant com.ex", "appops get com.ex", "pm clear com.ex",
            "monkey -p com.ex")) {
            assertTrue("No package match for $line",
                suggest(line).any { it.value == "com.example.app" })
        }
    }

    @Test fun permissionSuggestionsRespectInstalledAppDeclaration() {
        val suggestions = suggest("pm grant com.example.app android.permission.")
        assertEquals(
            listOf("android.permission.CAMERA",
                "android.permission.WRITE_SECURE_SETTINGS"),
            suggestions.map { it.value })
        assertTrue(suggestions.all { it.kind == CompletionKind.PERMISSION })
        assertTrue(suggest("pm grant com.example.app android.permission.DUMP").isEmpty())
    }

    @Test fun fullAndroidPermissionNamesAndShortPrefixesComplete() {
        assertTrue(suggest("pm revoke com.example.tools DU").any {
            it.value == "android.permission.DUMP"
        })
        assertTrue(suggest("cmd package grant com.example.app android.perm")
            .any { it.value == "android.permission.CAMERA" })
        assertTrue(suggest("pm grant com.example.app ").isNotEmpty())
    }

    @Test fun privateBookmarksAndSessionHistoryComeBeforeStaticCatalog() {
        val results = suggest("pm li",
            history = listOf("pm list packages -3"),
            bookmarks = listOf("pm list packages -s"))
        assertEquals("pm list packages -s", results[0].value)
        assertEquals(CompletionKind.BOOKMARK, results[0].kind)
        assertEquals(CompletionKind.HISTORY, results[1].kind)
        assertEquals(results.map { it.value }.distinct(), results.map { it.value })
    }

    @Test fun adbPrefixNowOffersRunnableShellAliasesNotHostVerbs() {
        assertTrue(suggest("adb").any { it.value == "adb shell" })
        assertTrue(suggest("adb s").any { it.value == "adb shell id" })
        assertTrue(suggest("adb shell pm li").any {
            it.value == "pm list packages"
        })
        assertTrue(suggest("adb devices").isEmpty())
        assertTrue(suggest("adb connect").isEmpty())
        val packageHint = suggest("adb shell pm path com.ex")
        assertTrue(packageHint.any { it.value == "com.example.app" })
        val hint = suggest("adb shell pm grant com.example.app CA")
        assertTrue(hint.any { it.value == "android.permission.CAMERA" })
        val selected = suggest("adb").first { it.value == "adb shell" }
        val applied = AdbCommandCompletion.apply("adb", selected)!!
        assertEquals("adb shell ", applied.text)
        assertTrue(suggest("adb shell ").any { it.value == "id" })
        val engine = java.io.File(
            "src/main/java/dev/n2k02/localadb/AdbCommandCompletion.kt").readText()
        assertFalse(engine.contains("AdbTransport.exec("))
        assertFalse(engine.contains("AdbConsole.execute("))
        assertFalse(engine.contains("Runtime.getRuntime("))
    }

    @Test fun doesNotExecuteOrOfferHostSideAdbCommands() {
        val engine = java.io.File(
            "src/main/java/dev/n2k02/localadb/AdbCommandCompletion.kt").readText()
        assertFalse(engine.contains("AdbTransport.exec("))
        assertFalse(engine.contains("AdbConsole.execute("))
        assertFalse(engine.contains("Runtime.getRuntime("))
    }

    @Test fun midWordInsertionReplacesWholeTokenAndPreservesSuffix() {
        val line = "pm grant com.exxx android.permission.DUMP"
        val caret = "pm grant com.ex".length
        val candidate = suggest(line, caret).first { it.value == "com.example.app" }
        val applied = AdbCommandCompletion.apply(line, candidate)!!
        assertEquals("pm grant com.example.app android.permission.DUMP", applied.text)
        assertEquals("pm grant com.example.app".length, applied.cursor)
    }

    @Test fun packageInsertionAtEndAddsSpaceForPermissionArgumentOnly() {
        val old = "pm grant com.ex"
        val candidate = suggest(old).first { it.value == "com.example.app" }
        val result = AdbCommandCompletion.apply(old, candidate)!!
        assertEquals("pm grant com.example.app ", result.text)
        assertEquals(result.text.length, result.cursor)

        val path = "pm path com.ex"
        val inserted = AdbCommandCompletion.apply(path, suggest(path).first())!!
        assertEquals("pm path com.example.app", inserted.text)
    }

    @Test fun commandCompletionPreservesEarlierShellSegments() {
        val source = "id; pm gr"
        val candidate = suggest(source).first { it.value == "pm grant" }
        val result = AdbCommandCompletion.apply(source, candidate)!!
        assertEquals("id; pm grant", result.text)
        assertEquals(result.text.length, result.cursor)
    }

    @Test fun staleSuggestionIsRejectedWithoutChangingInput() {
        val old = "pm grant com.ex"
        val candidate = suggest(old).first()
        assertNull(AdbCommandCompletion.apply("pm grant com.else", candidate))
        assertNull(AdbCommandCompletion.apply("", candidate))
    }

    @Test fun emptyOversizedAndInvalidCursorYieldNoSuggestions() {
        assertTrue(suggest("").isEmpty())
        assertTrue(suggest("  ").isEmpty())
        assertTrue(suggest("pm gr", -1).isEmpty())
        assertTrue(suggest("pm gr", 100).isEmpty())
        assertTrue(suggest("a".repeat(AdbConsoleProtocol.MAX_COMMAND_CHARS + 1)).isEmpty())
    }

    @Test fun suggestionsStayBoundedEvenWhenIndexIsHuge() {
        val huge = CompletionIndex(packages = (0..1000).map { "com.example.app$it" })
        val results = AdbCommandCompletion.suggestions("pm path com.ex", 14,
            huge, limit = 900)
        assertEquals(AdbCommandCompletion.MAX_SUGGESTIONS, results.size)
    }

    @Test fun foreignCommandOrPropertyNamesDoNotLeakIntoPackageSuggestions() {
        assertTrue(suggest("getprop ro.build.s").all { it.kind != CompletionKind.PACKAGE })
        assertTrue(suggest("cat /sdcard/file.txt").all { it.kind != CompletionKind.PACKAGE })
        assertTrue(suggest("pm path /sdcard").isEmpty())
    }

    @Test fun dynamicPackageScanIsReadOnlyAndDoesNotRequireAdbReconnect() {
        val loader = java.io.File(
            "src/main/java/dev/n2k02/localadb/AdbCompletionIndexLoader.kt").readText()
        assertTrue(loader.contains("PackageManager.GET_PERMISSIONS"))
        assertTrue(loader.contains("Dispatchers.IO"))
        assertFalse(loader.contains("AdbTransport.exec"))
        assertFalse(loader.contains("NativeShizukuManager."))
        assertFalse(loader.contains("pm grant"))
    }

    @Test fun completionNeverInventsAutoExecutionOrEmitsSensitiveData() {
        val screen = java.io.File(
            "src/main/java/dev/n2k02/localadb/AdbConsolePage.kt").readText()
        assertTrue(screen.contains("AdbCommandCompletion.suggestions("))
        assertTrue(screen.contains("AdbCommandCompletion.apply("))
        assertTrue(screen.contains("TextFieldValue("))
        assertTrue(screen.contains("Key.Tab"))
        assertTrue(screen.contains("Tap or Tab"))
        assertTrue(screen.contains("never runs automatically"))
        assertTrue(screen.contains("AdbConsole.execute(input.text)"))
    }
}

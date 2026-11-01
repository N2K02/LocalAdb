package dev.n2k02.localadb

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Test

class AdbConsoleUiTest {
    private fun source() = java.io.File(
        "src/main/java/dev/n2k02/localadb/AdbConsolePage.kt"
    ).readText()

    @Test fun noDuplicatedPageHeadingOrPasteButton() {
        val code = source()
        assertFalse(code.contains("Text(\"ADB shell\","))
        assertEquals(1, "Icons.Rounded.ContentPaste".toRegex().findAll(code).count())
        assertTrue(code.contains("Text(\"Connection\")"))
    }
    @Test fun filteredSearchResultsCannotCauseOutOfBoundsScroll() {
        val code = source()
        assertTrue(code.contains("LaunchedEffect(filtered.size, search)"))
        assertTrue(code.contains("list.animateScrollToItem(filtered.lastIndex)"))
        assertFalse(code.contains("list.animateScrollToItem(console.entries.size - 1)"))
    }
    @Test fun consolePanelsAndResultBadgesUseLiveData() {
        val code = source()
        assertTrue(code.contains("ModernSectionHeader("))
        assertTrue(code.contains("ModernPanel("))
        assertTrue(code.contains("ModernStatusChip("))
        assertTrue(code.contains("console.entries.size.toString()"))
        assertTrue(code.contains("entry.exitCode == 0"))
        assertTrue(code.contains("DashboardTone.ERROR"))
        assertTrue(code.contains("DashboardTone.GOOD"))
    }

    @Test fun commandHistoryBookmarksAndAbortAreRetained() {
        val code = source()
        assertTrue(code.contains("AdbConsole.execute(input)"))
        assertTrue(code.contains("AdbConsole.interrupt()"))
        assertTrue(code.contains("AdbConsole.loadBookmarks(context)"))
        assertTrue(code.contains("Search command output"))
        assertTrue(code.contains("Copy output"))
        assertTrue(code.contains("Paste from clipboard"))
    }
}

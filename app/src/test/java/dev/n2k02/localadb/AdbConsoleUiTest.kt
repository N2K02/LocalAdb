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
        assertTrue(code.contains("contentDescription = \"Connection\""))
    }
    @Test fun filteredSearchResultsCannotCauseOutOfBoundsScroll() {
        val code = source()
        assertTrue(code.contains("LaunchedEffect(console.entries.lastOrNull(), search)"))
        assertTrue(code.contains("list.scrollToItem(0)"))
        assertTrue(code.contains("items(newestFirst)"))
        assertTrue(code.contains("filtered.asReversed()"))
        assertFalse(code.contains("list.animateScrollToItem(console.entries.size - 1)"))
    }
    @Test fun consolePanelsAndResultBadgesUseLiveData() {
        val code = source()
        assertFalse(code.contains("title = \"Command console\""))
        assertTrue(code.contains("ModernPanel("))
        assertTrue(code.contains("ModernStatusChip("))
        assertTrue(code.contains("console.entries.size.toString()"))
        assertTrue(code.contains("entry.exitCode == 0"))
        assertTrue(code.contains("DashboardTone.ERROR"))
        assertTrue(code.contains("DashboardTone.GOOD"))
    }

    @Test fun completionOnlyInsertsAndDoesNotExecute() {
        val code = source()
        assertTrue(code.contains("AdbCommandCompletion.suggestions("))
        assertTrue(code.contains("AdbCommandCompletion.apply("))
        val navigation = java.io.File(
            "src/main/java/dev/n2k02/localadb/LocalAdbScreen.kt").readText()
        assertTrue(navigation.contains("TextFieldValue.Saver"))
        assertTrue(code.contains("onPreviewKeyEvent"))
        assertTrue(code.contains("Key.Tab"))
        assertTrue(code.contains("Tap or Tab"))
        assertTrue(code.contains("AdbCompletionIndexLoader.load(context)"))
        assertTrue(code.contains("contentDescription = \"Commands\""))
        assertTrue(code.contains("suppressSuggestionsFor = input.text"))
    }

    @Test fun newestResultIsExpandedAndOlderResultsAreCollapsedUntilSelected() {
        val code = source()
        assertTrue(code.contains("val newestFirst = remember(filtered) { filtered.asReversed() }"))
        assertTrue(code.contains("expandedEntry = console.entries.lastOrNull()"))
        assertTrue(code.contains("val expanded = expandedEntry === entry"))
        assertTrue(code.contains("expandedEntry = if (expanded) null else entry"))
        assertTrue(code.contains("if (expanded) {"))
        assertTrue(code.contains("Collapse output"))
        assertTrue(code.contains("Expand output"))
        assertTrue(code.contains("Copy output"))
        assertTrue(code.contains("list.scrollToItem(0)"))
    }

    @Test fun commandsButtonIsSeparateBrowsePageNotAutoExecution() {
        val code = source()
        val main = java.io.File(
            "src/main/java/dev/n2k02/localadb/LocalAdbScreen.kt").readText()
        val catalog = java.io.File(
            "src/main/java/dev/n2k02/localadb/CommandLibraryPage.kt").readText()
        assertTrue(code.contains("IconButton(onClick = onOpenCommands"))
        assertTrue(main.contains("Page.COMMAND_LIBRARY -> CommandLibraryPage("))
        assertTrue(main.contains("adbDraft = TextFieldValue(selected"))
        assertTrue(catalog.contains("onUse(entry.command)"))
        assertTrue(catalog.contains("entry.usableInLocalAdb"))
        assertFalse(catalog.contains("AdbConsole.execute("))
        assertFalse(catalog.contains("AdbTransport.exec"))
    }

    @Test fun outputAreaUsesTabsAndCheapSurfacesInsteadOfGradientResultCards() {
        val code = source()
        assertTrue(code.contains("TabRow(selectedTabIndex = selectedTab)"))
        assertTrue(code.contains("listOf(\"Output\", \"History\", \"Saved\")"))
        assertTrue(code.contains("Surface(modifier = Modifier.fillMaxWidth()"))
        assertTrue(code.contains("selectContentTab(0)"))
        assertFalse(code.contains("title = \"Command console\""))
        assertTrue(code.contains("Modifier.weight(1f).fillMaxWidth()"))
        assertTrue(code.contains("showFullOutput"))
        assertTrue(code.contains("Show full output"))
    }

    @Test fun compactTerminalMatchesApprovedLogcatMock() {
        val code = source()
        assertTrue(code.contains("Terminal-style input bar"))
        assertTrue(code.contains("Text(\"Run\", style = MaterialTheme.typography.titleSmall)"))
        assertFalse(code.contains("Text(\"Run command\""))
        assertTrue(code.contains("BasicTextField("))
        assertTrue(code.contains("shape = RoundedCornerShape(11.dp)"))
        assertTrue(code.contains("modifier = Modifier.weight(1f).height(46.dp)"))
        assertTrue(code.contains("fontSize = 12.sp"))
        assertTrue(code.contains("lineHeight = 15.sp"))
        assertTrue(code.contains("softWrap = false"))
        assertTrue(code.contains("horizontalScroll(rememberScrollState())"))
        assertTrue(code.contains("CompactSearchField("))
        assertTrue(code.contains("IconButton(onClick = onOpenCommands"))
        assertTrue(code.contains("contentDescription = \"Connection\""))
        assertTrue(code.contains("if (console.running) AdbConsole.interrupt() else execute()"))
    }

    @Test fun adbSubtabsSwipeAndBackInVisitOrder() {
        val code = source()
        assertTrue(code.contains("TabVisitHistory.select(tabHistory, tab)"))
        assertTrue(code.contains("BackHandler(enabled = tabHistory.size > 1)"))
        assertTrue(code.contains("TabVisitHistory.pop(tabHistory)"))
        assertTrue(code.contains("onClick = { selectContentTab(index) }"))
        assertTrue(code.contains("onSwipeLeft = { selectContentTab((selectedTab + 1).coerceAtMost(2)) }"))
        assertTrue(code.contains("onSwipeRight = { selectContentTab((selectedTab - 1).coerceAtLeast(0)) }"))
        assertTrue(code.contains("consumeOnNavigation = true"))
        assertTrue(code.contains("selectContentTab(0)"))
    }

    @Test fun commandHistoryBookmarksAndAbortAreRetained() {
        val code = source()
        assertTrue(code.contains("AdbConsole.execute(input.text)"))
        assertTrue(code.contains("AdbConsole.interrupt()"))
        assertTrue(code.contains("AdbConsole.loadBookmarks(context)"))
        assertTrue(code.contains("Search command output"))
        assertTrue(code.contains("Copy output"))
        assertTrue(code.contains("Paste from clipboard"))
    }
}

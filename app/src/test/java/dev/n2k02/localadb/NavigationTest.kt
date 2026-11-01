package dev.n2k02.localadb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class NavigationTest {
    @Test fun settingsSubpagesReturnToSettings() {
        listOf(Page.ADB_CONNECTION, Page.UPDATES).forEach {
            assertEquals(Page.SETTINGS, Navigation.back(it))
        }
    }
    @Test fun commandsSubmenuReturnsToAdb() {
        assertEquals(Page.ADB, Navigation.back(Page.COMMAND_LIBRARY))
        val code = java.io.File("src/main/java/dev/n2k02/localadb/LocalAdbScreen.kt")
            .readText()
        assertTrue(code.contains("Page.COMMAND_LIBRARY -> \"Commands\""))
        assertTrue(code.contains("Page.COMMAND_LIBRARY -> Icons.Rounded.MenuBook"))
        assertTrue(code.contains("Page.ADB || page == Page.COMMAND_LIBRARY"))
    }
    @Test fun mainTabsReturnHomeOnBack() {
        listOf(Page.HOME, Page.APPS, Page.ADB, Page.SETTINGS).forEach {
            assertEquals(Page.HOME, Navigation.back(it))
        }
    }
    @Test fun everyMainTabCanBeSelectedFromEveryPage() {
        Page.entries.forEach { page ->
            for (tab in listOf(Page.HOME, Page.APPS, Page.ADB, Page.SETTINGS)) {
                assertEquals(tab, Navigation.selectTab(page, tab))
            }
        }
    }
    @Test fun settingsSubpagesCannotBecomeMainTabs() {
        listOf(Page.ADB_CONNECTION, Page.UPDATES, Page.COMMAND_LIBRARY).forEach { sub ->
            assertThrows(IllegalArgumentException::class.java) {
                Navigation.selectTab(Page.HOME, sub)
            }
        }
    }
    @Test fun bottomNavigationHasFiveEntriesAndHomeHasNoDuplicateActions() {
        val source = java.io.File("src/main/java/dev/n2k02/localadb/LocalAdbScreen.kt").readText()
        assertEquals(5, source.split("NavigationBarItem(").size - 1)
        assertTrue(source.contains("onClick = { selectMainTab(Page.APPS) }"))
        assertTrue(source.contains("onClick = { selectMainTab(Page.ADB) }"))
        val home = source.substringAfter("private fun HomePage(")
            .substringBefore("private fun SettingsPage(")
        assertFalse(home.contains("onOpenShell"))
        assertFalse(home.contains("onOpenApps"))
        assertTrue(home.contains("ModernPanel("))
        assertTrue(home.contains("NativeShizukuManager.stop(context)"))
        assertTrue(home.contains("NativeShizukuManager.restart(context)"))
        val settings = source.substringAfter("private fun SettingsPage(").substringBefore("private fun UpdatesPage(")
        assertTrue(settings.contains("Page.ADB_CONNECTION"))
        assertFalse(settings.contains("onNavigate(Page.APPS)"))
        assertFalse(settings.contains("onNavigate(Page.ADB)"))
    }
    @Test fun mainTabHistoryReturnsToPreviouslyVisitedTab() {
        var trail = listOf(Page.HOME.name)
        trail = TabVisitHistory.select(trail, Page.APPS.name)
        trail = TabVisitHistory.select(trail, Page.ADB.name)
        trail = TabVisitHistory.select(trail, Page.ADB.name)
        assertEquals(3, trail.size)
        var previous = TabVisitHistory.pop(trail)
        assertEquals(Page.APPS.name, previous.second)
        previous = TabVisitHistory.pop(previous.first)
        assertEquals(Page.HOME.name, previous.second)
        assertEquals(null, TabVisitHistory.pop(previous.first).second)
    }

    @Test fun swipeOnlyChangesMainTabsAndNeverQuitOrSubpages() {
        val source = java.io.File("src/main/java/dev/n2k02/localadb/LocalAdbScreen.kt")
            .readText()
        assertTrue(source.contains("val mainTabs = listOf(Page.HOME, Page.APPS, Page.ADB, Page.SETTINGS)"))
        assertTrue(source.contains(".swipeTabNavigation("))
        assertTrue(source.contains("currentIndex < mainTabs.lastIndex"))
        assertTrue(source.contains("currentIndex > 0"))
        assertTrue(source.contains("TabVisitHistory.pop(visitedTabs)"))
        assertTrue(source.contains("Page.COMMAND_LIBRARY, Page.ADB_CONNECTION, Page.UPDATES"))
    }

    @Test fun swipeDoesNotStealVerticalScrollOrHorizontalTerminalSelection() {
        val source = java.io.File("src/main/java/dev/n2k02/localadb/TabSwipeNavigation.kt")
            .readText()
        assertTrue(source.contains("awaitPointerEvent(PointerEventPass.Main)"))
        assertTrue(source.contains("if (change.isConsumed) childHandled = true"))
        assertTrue(source.contains("abs(dx) > 1.7f * abs(dy)"))
        assertTrue(source.contains("if (consumeOnNavigation) change.consume()"))
    }

    @Test fun headerKeepsAppIconCompactAndUsesOneTopBar() {
        val source = java.io.File("src/main/java/dev/n2k02/localadb/LocalAdbScreen.kt").readText()
        assertTrue(source.contains("Modifier.size(25.dp)"))
        assertEquals(1, source.split("TopAppBar(").size - 1)
    }
    @Test fun quitShutsDownCommandAndShizukuBeforeAdb() {
        val source = java.io.File("src/main/java/dev/n2k02/localadb/MainActivity.kt").readText()
        val quit = source.substringAfter("private fun quit()")
        assertTrue(quit.indexOf("AdbConsole.shutdown()") < quit.indexOf("AdbEngine.shutdown("))
        assertTrue(quit.indexOf("NativeShizukuManager.shutdown(") < quit.indexOf("AdbEngine.shutdown("))
        assertTrue(quit.contains("finishAndRemoveTask()"))
    }
}

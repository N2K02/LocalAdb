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
        listOf(Page.ADB_CONNECTION, Page.UPDATES).forEach { sub ->
            assertThrows(IllegalArgumentException::class.java) {
                Navigation.selectTab(Page.HOME, sub)
            }
        }
    }
    @Test fun bottomNavigationHasFiveEntriesAndHomeHasNoDuplicateActions() {
        val source = java.io.File("src/main/java/dev/n2k02/localadb/LocalAdbScreen.kt").readText()
        assertEquals(5, source.split("NavigationBarItem(").size - 1)
        assertTrue(source.contains("onClick = { page = Navigation.selectTab(page, Page.APPS) }"))
        assertTrue(source.contains("onClick = { page = Navigation.selectTab(page, Page.ADB) }"))
        val home = source.substringAfter("private fun HomePage(").substringBefore("private fun StatusLabel(")
        assertFalse(home.contains("clickable("))
        assertFalse(home.contains("Button("))
        assertFalse(home.contains("onOpenShell"))
        assertFalse(home.contains("onOpenApps"))
        val settings = source.substringAfter("private fun SettingsPage(").substringBefore("private fun UpdatesPage(")
        assertTrue(settings.contains("Page.ADB_CONNECTION"))
        assertFalse(settings.contains("onNavigate(Page.APPS)"))
        assertFalse(settings.contains("onNavigate(Page.ADB)"))
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

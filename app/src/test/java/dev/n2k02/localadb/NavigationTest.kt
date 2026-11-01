package dev.n2k02.localadb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationTest {
    @Test fun allSettingsSubpagesReturnToSettings() {
        listOf(Page.ADB_CONNECTION, Page.APPS, Page.UPDATES).forEach {
            assertEquals(Page.SETTINGS, Navigation.back(it))
        }
    }
    @Test fun backFromTopLevelReturnsHome() {
        assertEquals(Page.HOME, Navigation.back(Page.SETTINGS))
        assertEquals(Page.HOME, Navigation.back(Page.ADB))
        assertEquals(Page.HOME, Navigation.back(Page.HOME))
    }
    @Test fun everyPageMaySwitchToEveryMainTab() {
        Page.entries.forEach { page ->
            for (tab in listOf(Page.HOME, Page.ADB, Page.SETTINGS)) {
                assertEquals(tab, Navigation.selectTab(page, tab))
            }
        }
    }
    @Test fun settingsSubpagesCannotBecomeTopTabs() {
        listOf(Page.ADB_CONNECTION, Page.APPS, Page.UPDATES).forEach { sub ->
            assertThrows(IllegalArgumentException::class.java) {
                Navigation.selectTab(Page.HOME, sub)
            }
        }
    }
    @Test fun quitClosesConsoleBeforeTransport() {
        val source = java.io.File("src/main/java/dev/n2k02/localadb/MainActivity.kt").readText()
        val quit = source.substringAfter("private fun quit()")
        assertTrue(quit.indexOf("AdbConsole.shutdown()") < quit.indexOf("AdbEngine.shutdown("))
        assertTrue(quit.contains("finishAndRemoveTask()"))
    }
}

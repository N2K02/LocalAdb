package dev.n2k02.localadb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NavigationTest {
    @Test fun subpagesReturnToSettings() {
        listOf(Page.ADB, Page.UPDATES).forEach {
            assertEquals(Page.SETTINGS, Navigation.back(it))
        }
    }
    @Test fun backFromSettingsReturnsHome() {
        assertEquals(Page.HOME, Navigation.back(Page.SETTINGS))
        assertEquals(Page.HOME, Navigation.back(Page.HOME))
    }
    @Test fun tabSwitchResetsSubpages() {
        Page.entries.forEach { page ->
            assertEquals(Page.HOME, Navigation.selectTab(page, Page.HOME))
            assertEquals(Page.SETTINGS, Navigation.selectTab(page, Page.SETTINGS))
        }
    }
    @Test fun adbIsOnlyASubpage() {
        assertThrows(IllegalArgumentException::class.java) {
            Navigation.selectTab(Page.HOME, Page.ADB)
        }
    }
}

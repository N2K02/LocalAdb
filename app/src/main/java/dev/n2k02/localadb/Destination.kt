package dev.n2k02.localadb

internal enum class Page { HOME, ADB, SETTINGS, ADB_CONNECTION, APPS, UPDATES }

internal object Navigation {
    fun selectTab(page: Page, tab: Page): Page {
        require(tab == Page.HOME || tab == Page.ADB || tab == Page.SETTINGS)
        return tab
    }

    fun back(page: Page): Page = when (page) {
        Page.ADB_CONNECTION, Page.APPS, Page.UPDATES -> Page.SETTINGS
        Page.SETTINGS, Page.ADB -> Page.HOME
        Page.HOME -> Page.HOME
    }
}

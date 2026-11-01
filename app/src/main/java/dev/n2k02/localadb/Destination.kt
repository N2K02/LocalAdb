package dev.n2k02.localadb

/** Four main tabs plus Settings subpages. Quit is an action, not a page. */
internal enum class Page { HOME, APPS, ADB, SETTINGS, ADB_CONNECTION, UPDATES }

internal object Navigation {
    fun selectTab(page: Page, tab: Page): Page {
        require(tab in listOf(Page.HOME, Page.APPS, Page.ADB, Page.SETTINGS))
        return tab
    }

    fun back(page: Page): Page = when (page) {
        Page.ADB_CONNECTION, Page.UPDATES -> Page.SETTINGS
        Page.APPS, Page.ADB, Page.SETTINGS, Page.HOME -> Page.HOME
    }
}

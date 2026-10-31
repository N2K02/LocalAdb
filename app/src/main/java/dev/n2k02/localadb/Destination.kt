package dev.n2k02.localadb

internal enum class Page { HOME, SETTINGS, DIAGNOSTICS, LOGS, UPDATES }

internal object Navigation {
    fun selectTab(page: Page, tab: Page): Page {
        require(tab == Page.HOME || tab == Page.SETTINGS)
        return tab
    }

    fun back(page: Page): Page = when (page) {
        Page.DIAGNOSTICS, Page.LOGS, Page.UPDATES -> Page.SETTINGS
        Page.SETTINGS -> Page.HOME
        Page.HOME -> Page.HOME
    }
}

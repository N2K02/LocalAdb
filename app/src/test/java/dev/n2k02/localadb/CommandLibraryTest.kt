package dev.n2k02.localadb

import org.junit.Assert.*
import org.junit.Test

class CommandLibraryTest {
    @Test fun hasUniqueExamplesForEveryCategory() {
        assertEquals(CommandLibrary.entries.size, CommandLibrary.entries.map { it.command }.toSet().size)
        CommandCategory.entries.forEach { cat ->
            assertTrue("No examples for undefined", CommandLibrary.entries.any { it.category == cat })
        }
        assertTrue(CommandLibrary.entries.size >= 45)
    }

    @Test fun queryMatchesTitlesDescriptionsCommandsAndCategories() {
        assertTrue(CommandLibrary.search("battery").any { it.command == "dumpsys battery" })
        assertTrue(CommandLibrary.search("package").any { it.command == "pm list packages" })
        assertTrue(CommandLibrary.search("permission").isNotEmpty())
        assertTrue(CommandLibrary.search("nonexistent-device-verb").isEmpty())
        assertTrue(CommandLibrary.search("    ").size == CommandLibrary.entries.size)
        assertTrue(CommandLibrary.search("android version").any { it.command.contains("ro.build.version") })
    }

    @Test fun categoryFilterAndQueryApplyTogether() {
        val items = CommandLibrary.search("pm", CommandCategory.APPS)
        assertTrue(items.isNotEmpty())
        assertTrue(items.all { it.category == CommandCategory.APPS })
        assertTrue(CommandLibrary.search("battery", CommandCategory.HOST).isEmpty())
    }

    @Test fun hostAdbCommandsAreReferenceOnlyNotExecutable() {
        val host = CommandLibrary.search("", CommandCategory.HOST)
        assertTrue(host.isNotEmpty())
        assertTrue(host.all { !it.usableInLocalAdb && it.risk == CommandRisk.HOST_ONLY })
        assertTrue(CommandLibrary.entries.filter { it.usableInLocalAdb }
            .none { it.command.startsWith("adb ") })
    }

    @Test fun templatesRequireManualEditingAndAreNotAutoexecuted() {
        val pkg = CommandLibrary.entries.first { it.command == "pm grant <package> <permission>" }
        assertTrue(pkg.hasPlaceholders)
        assertEquals(CommandRisk.CHANGES_DEVICE, pkg.risk)
        assertFalse(CommandLibrary.entries.first { it.command == "id" }.hasPlaceholders)
    }

    @Test fun canSearchByMultipleTermsInAnyOrder() {
        val result = CommandLibrary.search("package list")
        assertTrue(result.any { it.command == "pm list packages" })
    }
}

package dev.n2k02.localadb

import org.junit.Assert.*
import org.junit.Test

class AppListLayoutTest {
    private val source = java.io.File("src/main/java/dev/n2k02/localadb/AppsPage.kt")

    @Test fun startsOnShizukuClientsButCanShowAllAppsAndAndroidGrants() {
        val code = source.readText()
        assertTrue(code.contains("var shizukuOnly by remember { mutableStateOf(true) }"))
        assertTrue(code.contains("Text(\"All apps\")"))
        assertTrue(code.contains("Text(\"Android granted\")"))
        assertTrue(code.contains("Text(\"Android revoked\")"))
        assertTrue(code.contains("Text(\"Hidden\")") || code.contains("\"Hidden\""))
    }
    @Test fun noDuplicateIntroductionAndAndroidPermissionsCanCollapse() {
        val code = source.readText()
        assertFalse(code.contains("Text(\"App access\", style"))
        assertFalse(code.contains("Text(\"Android developer permissions\","))
        assertTrue(code.contains("var androidExpanded"))
        assertTrue(code.contains("if (androidExpanded) for (permission"))
        assertTrue(code.contains("if (androidExpanded) \"Hide\" else \"Show\""))
    }
    @Test fun shizukuAndAndroidPermissionTogglesStaySeparate() {
        val code = source.readText()
        assertTrue(code.contains("NativeShizukuManager.changeGrant("))
        assertTrue(code.contains("AppAccessRepository.changeAndroidGrant("))
        assertTrue(code.contains("shizukuGrants[app.uid]"))
        assertTrue(code.contains("permission in app.grantableAdbPermissions"))
    }
}

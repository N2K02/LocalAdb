package dev.n2k02.localadb

import org.junit.Assert.*
import org.junit.Test

class AppListLayoutTest {
    private val source = java.io.File("src/main/java/dev/n2k02/localadb/AppsPage.kt")

    @Test fun defaultShizukuAndCombinedMultiSelectFilters() {
        val code = source.readText()
        assertTrue(code.contains("mutableStateOf(setOf(\"Shizuku\"))"))
        assertTrue(code.contains("selectedTypes"))
        assertTrue(code.contains("selectedAccess"))
        assertTrue(code.contains("DropdownMenu("))
        assertTrue(code.contains("Checkbox(checked = kind in selectedTypes"))
        assertTrue(code.contains("Checkbox(checked = access in selectedAccess"))
        assertTrue(code.contains("\"Android granted\""))
        assertTrue(code.contains("\"Android revoked\""))
        assertTrue(code.contains("\"Hidden\""))
        assertTrue(code.contains("descending = !descending"))
    }

    @Test fun onlyBadgesAboveListAndCollapsedCompactRows() {
        val code = source.readText()
        assertFalse(code.contains("Text(\"App permissions\""))
        assertFalse(code.contains("Text(\"Android developer permissions\""))
        assertTrue(code.contains("numberOfClients.toString()"))
        assertTrue(code.contains("numberOfAndroidGrants.toString()"))
        assertTrue(code.contains("items(visible, key ="))
        assertTrue(code.contains("var appExpanded"))
        assertTrue(code.contains("if (appExpanded)"))
        assertTrue(code.contains("var androidExpanded"))
        assertTrue(code.contains("if (androidExpanded) for (permission"))
        assertTrue(code.contains("if (androidExpanded) \"Hide\" else \"Show\""))
        assertFalse(code.contains("ModernPanel("))
        assertTrue(code.contains("surfaceContainerLow"))
    }

    @Test fun mockUsesCompactRoundedSearchAndBadgeCard() {
        val code = source.readText()
        val controls = java.io.File(
            "src/main/java/dev/n2k02/localadb/CompactControls.kt").readText()
        assertTrue(code.contains("CompactSearchField("))
        assertTrue(code.contains("shape = RoundedCornerShape(20.dp)"))
        assertTrue(code.contains("if (adb.connected) \"Ready\" else \"Offline\""))
        assertTrue(code.contains("if (adb.tcpConnected) \"TCP/5555\" else \"Wireless TLS\""))
        assertTrue(code.contains("numberOfClients.toString()"))
        assertTrue(code.contains("numberOfAndroidGrants.toString()"))
        assertTrue(controls.contains("RoundedCornerShape(23.dp)"))
        assertTrue(controls.contains("modifier = modifier.height(46.dp)"))
        assertTrue(controls.contains("BasicTextField("))
        assertTrue(controls.contains("modifier = modifier"))
    }

    @Test fun permissionSwitchesAreActuallySmallerNotJustScaledVisually() {
        val code = source.readText()
        val controls = java.io.File(
            "src/main/java/dev/n2k02/localadb/CompactControls.kt").readText()
        assertEquals(2, "CompactPermissionSwitch\\(".toRegex().findAll(code).count())
        assertFalse(code.lineSequence().any { it.trimStart().startsWith("Switch(") })
        assertTrue(controls.contains(".size(width = 40.dp, height = 24.dp)"))
        assertTrue(controls.contains(".size(width = 48.dp, height = 44.dp)"))
        assertTrue(controls.contains("role = Role.Switch"))
        assertTrue(controls.contains("onValueChange = onCheckedChange"))
        assertTrue(code.contains("PendingShizukuGrant(app, wanted)"))
        assertTrue(code.contains("PendingAndroidGrant(app, permission, wanted)"))
    }

    @Test fun iconRenderingIsBoundedAndShizukuReadbacksOffMainThread() {
        val code = source.readText()
        assertTrue(code.contains("LruCache<String, ImageBitmap>(48)"))
        assertTrue(code.contains("val size = 64"))
        assertTrue(code.contains("withContext(Dispatchers.IO)"))
        assertTrue(code.contains("NativeShizukuManager.granted(context, app.uid)"))
        assertFalse(code.contains("NativeShizukuManager.stop(context)"))
        assertFalse(code.contains("NativeShizukuManager.restart(context)"))
    }

    @Test fun shizukuAndAndroidPermissionTogglesStaySeparateAndConfirmed() {
        val code = source.readText()
        assertTrue(code.contains("NativeShizukuManager.changeGrant("))
        assertTrue(code.contains("AppAccessRepository.changeAndroidGrant("))
        assertTrue(code.contains("shizukuGrants[app.uid]"))
        assertTrue(code.contains("permission in app.grantableAdbPermissions"))
        assertTrue(code.contains("AlertDialog("))
    }
}

package dev.n2k02.localadb

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ShizukuCollapsedCardTest {
    @Test fun fourMetricsRemainVisibleAndActionsStayCollapsedByDefault() {
        val source = File("src/main/java/dev/n2k02/localadb/LocalAdbScreen.kt").readText()
        assertTrue(source.contains("var shizukuExpanded by rememberSaveable { mutableStateOf(false) }"))
        assertTrue(source.contains("shizukuExpanded = !shizukuExpanded"))

        val header = source.indexOf("title = \"Shizuku manager\"")
        val guard = source.indexOf("if (shizukuExpanded) {", header)
        assertTrue(header >= 0 && guard > header)
        listOf("Binder", "Shell", "Clients", "Grants").forEach { label ->
            val metric = source.indexOf("HomeMetric(\"$label\"", header)
            assertTrue("$label must remain outside collapsed section", metric in (header + 1) until guard)
        }
        val stop = source.indexOf("NativeShizukuManager.stop(context)", guard)
        val restart = source.indexOf("NativeShizukuManager.restart(context)", guard)
        val helper = source.indexOf("LocalHelperDiagnostics.start(context)", guard)
        val token = source.indexOf("Text(\"Start / Stop intents\"", guard)
        assertTrue(stop > guard && restart > guard && helper > guard && token > guard)
    }
}

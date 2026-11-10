package dev.n2k02.localadb

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ShizukuCollapsedCardTest {
    @Test fun shizukuManagerStartsCollapsedAndKeepsControlsBehindExpansion() {
        val source = File("src/main/java/dev/n2k02/localadb/LocalAdbScreen.kt").readText()
        assertTrue(source.contains("var shizukuExpanded by rememberSaveable { mutableStateOf(false) }"))
        assertTrue(source.contains("if (shizukuExpanded) {"))
        assertTrue(source.contains("shizukuExpanded = !shizukuExpanded"))
        val guard = source.indexOf("if (shizukuExpanded) {")
        val controls = source.indexOf("HomeMetric(\"Binder\"", guard)
        val stop = source.indexOf("NativeShizukuManager.stop(context)", guard)
        val restart = source.indexOf("NativeShizukuManager.restart(context)", guard)
        assertTrue(guard >= 0 && controls > guard && stop > guard && restart > guard)
    }
}

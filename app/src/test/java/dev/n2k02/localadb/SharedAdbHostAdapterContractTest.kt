package dev.n2k02.localadb

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SharedAdbHostAdapterContractTest {
    @Test fun sharedAdbCoreIsPinnedWithoutLosingOriginalAuthorization() {
        val root = listOf(File("app"), File("../app")).first { it.isDirectory }
        val settings = root.resolve("../settings.gradle.kts").readText()
        val build = root.resolve("build.gradle.kts").readText()
        val src = root.resolve("src/main/java/dev/n2k02/localadb/SharedAdbHostAdapter.kt").readText()
        assertTrue(settings.contains("n2k02AdbCoreLibrary"))
        assertTrue(build.contains("dev.n2k02:adb-core:0.1.2"))
        assertTrue(src.contains("AdbIdentity.getOrCreate(ctx)"))
        assertTrue(src.contains("AdbSettings."))
        assertTrue(src.contains("manuallyStopped()"))
        assertTrue(src.contains("LibAdbWire"))
        assertTrue(src.contains("AdbCoreRegistry.install(it)"))
        assertTrue(src.contains("AdbCoreRegistry.uninstall(it)"))
        assertTrue(src.contains("it.quit()"))
        assertTrue(src.contains("NativeShizukuManager.start(app)"))
        assertTrue(src.contains("AdbEngine.publishVerified(adb)"))
        assertTrue(src.indexOf("AdbEngine.publishVerified(adb)") <
            src.indexOf("NativeShizukuManager.start(app)"))
        val engine = root.resolve("src/main/java/dev/n2k02/localadb/AdbEngine.kt").readText()
        assertTrue(engine.contains("status.value = statusOf(adb.state.value)"))
    }
}

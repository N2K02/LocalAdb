package dev.n2k02.localadb

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** A source-level gate plus the CI DEX-level release check. */
class ReleaseStartupRulesTest {
    @Test fun workManagerRoomDatabaseConstructorIsKeptForReflectiveStartup() {
        val rules = File("proguard-rules.pro").readText()
        assertTrue(rules.contains("-keep class androidx.work.impl.WorkDatabase_Impl"))
        assertTrue(rules.contains("public <init>();"))
        assertTrue(rules.contains("-keep class * extends androidx.room.RoomDatabase"))
    }

    @Test fun experimentalCiAlsoBuildsAndInspectsMinifiedRelease() {
        val workflow = File("../.github/workflows/build.yml").readText()
        assertTrue(workflow.contains("assembleRelease"))
        assertTrue(workflow.contains("verify_release_workmanager.py"))
        assertTrue(workflow.contains("app-release-unsigned.apk"))
    }
}

package dev.n2k02.localadb

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ApkInstallPolicyTest {
    @Test fun acceptsOnlyCanonicalSessionIds() {
        val good = UUID.randomUUID().toString()
        assertTrue(ApkInstallPolicy.validSession(good))
        assertFalse(ApkInstallPolicy.validSession("selected"))
        assertFalse(ApkInstallPolicy.validSession("../selected"))
        assertFalse(ApkInstallPolicy.validSession(good.uppercase()))
        assertFalse(ApkInstallPolicy.validSession("$good/evil"))
        assertFalse(ApkInstallPolicy.validSession(""))
    }

    @Test fun uniqueRequestsNeverShareAnId() {
        val a = UUID.randomUUID().toString()
        val b = UUID.randomUUID().toString()
        assertNotEquals(a, b)
        assertTrue(ApkInstallPolicy.validSession(a))
        assertTrue(ApkInstallPolicy.validSession(b))
    }

    @Test fun fileLengthIsStrictlyBounded() {
        assertFalse(ApkInstallPolicy.validLength(0))
        assertFalse(ApkInstallPolicy.validLength(-1))
        assertTrue(ApkInstallPolicy.validLength(1))
        assertTrue(ApkInstallPolicy.validLength(ApkInstallPolicy.MAX_APK_BYTES))
        assertFalse(ApkInstallPolicy.validLength(ApkInstallPolicy.MAX_APK_BYTES + 1))
    }

    @Test fun doesNotConfuseExistingAppWithFreshInstallation() {
        assertFalse(ApkInstallPolicy.installedChanged(null, null, null, null))
        assertFalse(ApkInstallPolicy.installedChanged(12, 100, 12, 100))
        assertTrue(ApkInstallPolicy.installedChanged(null, null, 1, 100))
        assertTrue(ApkInstallPolicy.installedChanged(12, 100, 13, 110))
        assertTrue(ApkInstallPolicy.installedChanged(12, 100, 12, 110))
    }

    @Test fun resultsRespectCancellationAndVerifiedChange() {
        assertEquals("Installation completed", ApkInstallPolicy.result(true, false, false))
        assertEquals("Installation completed", ApkInstallPolicy.result(false, true, false))
        assertEquals("Installation cancelled", ApkInstallPolicy.result(false, false, true))
        assertEquals("Installation was not confirmed",
            ApkInstallPolicy.result(false, false, false))
    }
}

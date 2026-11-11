package dev.n2k02.localadb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectInstallAttributionTest {
    private val policy = DirectInstallAttribution

    @Test fun playAttributionIsOptInAndMissingPlayFailsExplicitly() {
        assertNull(policy.installerOption(preferPlay = false, playInstalled = true))
        assertNull(policy.installerOption(preferPlay = false, playInstalled = false))
        assertEquals(policy.PLAY_PACKAGE,
            policy.installerOption(preferPlay = true, playInstalled = true))
        assertThrows(IllegalStateException::class.java) {
            policy.installerOption(preferPlay = true, playInstalled = false)
        }
    }

    @Test fun shellInstallerArgumentsAreRestrictedToPlayOnly() {
        assertEquals(listOf("install-create", "-r", "-t"),
            policy.sessionCreateArgs(null))
        assertEquals(listOf("install-create", "-r", "-t", "-i", "com.android.vending"),
            policy.sessionCreateArgs(policy.PLAY_PACKAGE))
        assertThrows(IllegalArgumentException::class.java) {
            policy.sessionCreateArgs("evil.package;rm -rf /")
        }
        assertThrows(IllegalArgumentException::class.java) {
            policy.sessionCreateArgs("com.android.shell")
        }
    }

    @Test fun actualInstallerOfRecordIsDistinguishedFromInitiator() {
        val source = DirectInstallAttribution.Source(
            available = true,
            initiating = "com.android.shell",
            installing = policy.PLAY_PACKAGE
        )
        assertTrue(policy.verdict(policy.PLAY_PACKAGE, source).contains("not Play initiation"))
        assertTrue(policy.sourceDetails(policy.PLAY_PACKAGE, source)
            .contains("Initiating package: com.android.shell"))
        assertTrue(policy.sourceDetails(policy.PLAY_PACKAGE, source)
            .contains("Installing package: com.android.vending"))
    }

    @Test fun mismatchedAndMissingReadbackDoNotClaimSuccess() {
        val mismatch = DirectInstallAttribution.Source(true, "com.android.shell", "com.android.shell")
        assertTrue(policy.verdict(policy.PLAY_PACKAGE, mismatch).contains("not recorded"))
        val missing = DirectInstallAttribution.Source(false, failure = "query denied")
        assertTrue(policy.verdict(policy.PLAY_PACKAGE, missing).contains("could not be verified"))
        assertTrue(policy.sourceDetails(policy.PLAY_PACKAGE, missing).contains("query denied"))
        assertFalse(policy.verdict(policy.PLAY_PACKAGE, mismatch).contains("not Play initiation"))
    }

    @Test fun defaultShellModeReportsObservedSourceWithoutPlayClaims() {
        val source = DirectInstallAttribution.Source(true, "com.android.shell", null)
        assertTrue(policy.verdict(null, source).contains("did not report an installer"))
        assertTrue(policy.sourceDetails(null, source).contains("Requested installer: System default"))
        assertTrue(policy.verdict(null, DirectInstallAttribution.Source(true,
            "com.android.shell", "com.android.shell")).contains("recorded by Android"))
    }
}

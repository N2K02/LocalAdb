package dev.n2k02.localadb

import dev.n2k02.adb.core.AdbCore
import dev.n2k02.adb.core.SharedAdbPairingAccessibilityService

/**
 * All pairing-code reading, mDNS handoff, retry and shutdown logic belongs to
 * n2k02AdbCoreLibrary. This tiny adapter only supplies LocalAdb's saved key.
 */
class AdbPairingAccessibilityService : SharedAdbPairingAccessibilityService() {
    override fun acquireCore(): AdbCore =
        SharedAdbHostAdapter.core(applicationContext)
}

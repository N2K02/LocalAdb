package dev.n2k02.localadb

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.n2k02.adb.core.AdbHomeSetupPage
import dev.n2k02.adb.core.AdbPageLifecycle

/** Home pairing UI is owned by the same library as Settings and diagnostics. */
@Composable
internal fun AdbHomeSetupCard() {
    val context = LocalContext.current
    val adb = remember(context.applicationContext) { SharedAdbHostAdapter.core(context) }
    AdbPageLifecycle(adb)
    val state by adb.state.collectAsState()
    if (state.connected) return
    AdbHomeSetupPage(adb, accessibilityEnabled =
        dev.n2k02.adb.core.SharedAdbPairingAccessibilityService.isEnabled(
            context, AdbPairingAccessibilityService::class.java))
}

package dev.n2k02.localadb

import android.os.Bundle
import dev.n2k02.adb.core.AdbNetworkAccess
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.n2k02.theme.N2K02Theme

class MainActivity : ComponentActivity() {
    // Android 17 blocks local discovery/TCP for target 37 until the user has
    // approved its Nearby devices permission. Manifest declaration alone is
    // insufficient; never launch connection attempts before approval.
    private val localNetworkPermission = AdbNetworkAccess.PERMISSION
    private val requestLocalNetwork = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            AdbEngine.start(applicationContext, explicit = true)
        } else {
            AdbEngine.status.value = AdbStatus(
                phase = Phase.NEEDS_HELP,
                message = "Allow Nearby devices for automatic ADB discovery and connection"
            )
        }
    }

    private fun canUseLocalNetwork(): Boolean = AdbNetworkAccess.granted(this)

    private fun startAdbWhenPermitted() {
        if (canUseLocalNetwork()) {
            AdbEngine.start(applicationContext, explicit = true)
        } else {
            AdbEngine.status.value = AdbStatus(
                phase = Phase.NEEDS_HELP,
                message = "Nearby devices permission required for automatic ADB pairing"
            )
            requestLocalNetwork.launch(localNetworkPermission)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LiveUpdater.init(applicationContext)
        AdbSettings.markForegroundOpened(applicationContext)
        startAdbWhenPermitted()
        enableEdgeToEdge()
        setContent { N2K02Theme { LocalAdbScreen(onQuit = ::quit) } }
    }
    override fun onResume() {
        super.onResume()
        LiveUpdater.onResume(this)
        // Handles permission approval from Android App Info without requiring
        // a manual Retry button. start() is idempotent.
        if (canUseLocalNetwork()) AdbEngine.start(applicationContext)
    }
    private var quitting = false

    private fun quit() {
        if (quitting) return
        quitting = true
        lifecycleScope.launch {
            // Keep Quit single-flight and off the main thread. One failing
            // subsystem must not prevent the remaining cleanup or task removal.
            try {
                withContext(NonCancellable + Dispatchers.IO) {
                    try {
                        AdbConsole.shutdown()
                    } finally {
                        try {
                            runCatching { AndroidAutoPackageInstaller.shutdown(applicationContext) }
                            NativeShizukuManager.shutdown(applicationContext)
                        } finally {
                            try {
                                AdbEngine.shutdown(applicationContext)
                            } finally {
                                runCatching { LiveUpdater.shutdown() }
                                runCatching { appLog.shutdown() }
                            }
                        }
                    }
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    finishAndRemoveTask()
                }
            }
        }
    }
}

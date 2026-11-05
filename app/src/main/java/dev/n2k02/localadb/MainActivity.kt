package dev.n2k02.localadb

import android.os.Bundle
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LiveUpdater.init(applicationContext)
        AdbEngine.start(applicationContext, explicit = true)
        enableEdgeToEdge()
        setContent { N2K02Theme { LocalAdbScreen(onQuit = ::quit) } }
    }
    override fun onResume() {
        super.onResume()
        LiveUpdater.onResume(this)
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

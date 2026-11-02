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
            // Quit is single-flight. Shut down Shizuku before releasing its ADB
            // transport; never block the Activity main thread with runBlocking.
            withContext(NonCancellable + Dispatchers.IO) {
                AdbConsole.shutdown()
                NativeShizukuManager.shutdown(applicationContext)
                AdbEngine.shutdown(applicationContext)
                LiveUpdater.shutdown()
                appLog.shutdown()
            }
            finishAndRemoveTask()
        }
    }
}

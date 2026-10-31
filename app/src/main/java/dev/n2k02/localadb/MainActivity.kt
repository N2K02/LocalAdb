package dev.n2k02.localadb

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
    private fun quit() {
        AdbEngine.shutdown(applicationContext)
        LiveUpdater.shutdown()
        appLog.shutdown()
        finishAndRemoveTask()
    }
}

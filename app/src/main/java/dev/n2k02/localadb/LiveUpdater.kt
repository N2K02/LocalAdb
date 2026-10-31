package dev.n2k02.localadb

import android.app.Activity
import android.content.Context
import android.os.Build
import dev.n2k02.updater.PublishedUpdateSource
import dev.n2k02.updater.UpdateConfig
import dev.n2k02.updater.UpdateInstallGuard
import dev.n2k02.updater.UpdaterController
import kotlinx.coroutines.flow.MutableStateFlow

internal object LiveUpdater {
    private lateinit var app: Context
    private var instance: UpdaterController? = null
    val experimentalEnabled = MutableStateFlow(false)
    val installGuard = UpdateInstallGuard { true }

    fun init(context: Context) {
        if (instance != null) return
        app = context.applicationContext
        experimentalEnabled.value = prefs().getBoolean("experimentalEnabled", false)
        instance = createController()
    }

    private fun prefs() = app.getSharedPreferences("app_updater", Context.MODE_PRIVATE)

    @Suppress("DEPRECATION")
    private fun versionCode(): Long {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }

    private fun createController() = UpdaterController(
        context = app,
        config = UpdateConfig(
            repository = "N2K02/LocalAdb",
            packageName = app.packageName,
            installedVersionCode = versionCode(),
            experimentalEnabled = experimentalEnabled.value,
        ),
        publishedSource = PublishedUpdateSource("https://n2k02.github.io/localadb/updates"),
        offerPreferences = prefs(),
    )
    val controller: UpdaterController get() = requireNotNull(instance) { "Updater not initialized" }

    fun setExperimentalEnabled(enabled: Boolean) {
        if (experimentalEnabled.value == enabled) return
        controller.cancel()
        prefs().edit().putBoolean("experimentalEnabled", enabled).apply()
        experimentalEnabled.value = enabled
        instance = createController()
        controller.check(manual = true)
    }

    fun onResume(activity: Activity) = controller.onHostResume(activity, installGuard)
    fun shutdown() { instance?.cancel() }
}

package dev.n2k02.localadb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns an explicitly user-started ADB APK install even after the installer dialog closes.
 * The progress is intentionally indeterminate: package-manager commit provides no overall percent.
 */
class ApkInstallForegroundService : Service() {
    companion object {
        const val ACTION_INSTALL = "dev.n2k02.localadb.action.INSTALL_APK"
        const val ACTION_RESULT = "dev.n2k02.localadb.action.INSTALL_RESULT"
        const val EXTRA_SESSION = "session"
        const val EXTRA_PACKAGE = "installed_package"
        const val EXTRA_NAME = "display_name"
        const val EXTRA_OLD_VERSION = "prior_version"
        const val EXTRA_OLD_UPDATE = "prior_update"
        const val EXTRA_SUCCESS = "success"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_INSTALLER = "installer"
        private const val CHANNEL = "apk_install_status"
        private const val ACTIVE_ID = 21030
        private const val RESULT_ID = 21031
        private val busy = AtomicBoolean(false)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, "APK installations", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun notification(title: String, message: String, ongoing: Boolean): Notification {
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_localadb)
            .setContentTitle(title)
            .setContentText(message.take(120))
            .setStyle(Notification.BigTextStyle().bigText(message.take(400)))
            .setOnlyAlertOnce(ongoing)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setProgress(if (ongoing) 0 else 0, 0, ongoing)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_INSTALL) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (!busy.compareAndSet(false, true)) return START_NOT_STICKY
        val session = intent.getStringExtra(EXTRA_SESSION).orEmpty()
        val pkg = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        val label = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { pkg }
        if (session.isBlank() || pkg.isBlank()) {
            busy.set(false)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        // startForeground is called immediately, before any potentially blocking APK operations.
        startForeground(ACTIVE_ID, notification("Installing $label", "Installing via ADB…", true))
        val priorVersion = if (intent.hasExtra(EXTRA_OLD_VERSION))
            intent.getLongExtra(EXTRA_OLD_VERSION, -1L) else null
        val priorUpdate = if (intent.hasExtra(EXTRA_OLD_UPDATE))
            intent.getLongExtra(EXTRA_OLD_UPDATE, -1L) else null
        scope.launch {
            var ok = false
            var message = ""
            val installer = DirectInstallAttribution.requestedInstaller(applicationContext)
            try {
                val result = withContext(Dispatchers.IO) {
                    val file = ApkInstallProvider.file(applicationContext, session)
                    AndroidAutoPackageInstaller.validateInstallCompatibility(applicationContext, file)
                    AndroidAutoPackageInstaller.installDirect(applicationContext, file)
                }
                check(result == "Success") { "Package manager: $result" }
                val installed = withContext(Dispatchers.IO) {
                    val current = packageManager.getPackageInfo(pkg, 0)
                    val code = if (Build.VERSION.SDK_INT >= 28) current.longVersionCode else {
                        @Suppress("DEPRECATION")
                        current.versionCode.toLong()
                    }
                    ApkInstallPolicy.installedChanged(
                        priorVersion, priorUpdate, code, current.lastUpdateTime
                    )
                }
                ok = installed
                message = if (installed) "$label installed successfully"
                    else "ADB reported success but installation could not be verified"
            } catch (failure: Exception) {
                message = "Installation failed: ${failure.message ?: failure.javaClass.simpleName}"
                Log.w("LocalAdbApkInstall", "Background install failed", failure)
            } finally {
                val details = if (ok) DirectInstallAttribution.sourceDetails(
                    installer, DirectInstallAttribution.readBack(applicationContext, pkg)
                ) else ""
                val completedMessage = if (details.isBlank()) message else "$message\n$details"
                appLog.append("APK installer: ${completedMessage.take(300)}")
                runCatching {
                    applicationContext.revokeUriPermission(
                        ApkInstallProvider.uri(applicationContext, session),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                    ApkInstallProvider.cleanup(applicationContext, session)
                }
                // Explicit, package-scoped broadcast for an installer screen still in view.
                sendBroadcast(Intent(ACTION_RESULT).setPackage(packageName)
                    .putExtra(EXTRA_SESSION, session)
                    .putExtra(EXTRA_SUCCESS, ok)
                    .putExtra(EXTRA_MESSAGE, completedMessage))
                runCatching { notifications.notify(
                    RESULT_ID, notification(
                        if (ok) "APK installed" else "APK installation failed",
                        message, false
                    )
                ) }.onFailure { Log.w("LocalAdbApkInstall", "Result notification unavailable", it) }
                stopForeground(STOP_FOREGROUND_REMOVE)
                busy.set(false)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        busy.set(false)
        super.onDestroy()
    }
}

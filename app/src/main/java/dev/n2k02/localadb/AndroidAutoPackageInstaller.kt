package dev.n2k02.localadb

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import java.io.FileOutputStream

/**
 * Installs a user-selected APK through Android's system installer, with Google Play
 * installing-package attribution. No root, no direct "pm install" and no silent install.
 * A successful metadata read-back does NOT guarantee Android Auto compatibility.
 */
internal object AndroidAutoPackageInstaller {
    data class State(
        val packageName: String? = null,
        val version: String = "",
        val sizeBytes: Long = 0L,
        val busy: Boolean = false,
        val pending: Boolean = false,
        val success: Boolean = false,
        val message: String = "Select a compatible Android Auto APK to install.",
        // Structured read-back is independent of the free-form error message.
        val initiatingPackage: String? = null,
        val installingPackage: String? = null,
        val installMethod: String? = null,
        val details: String = "",
    )
    val state = MutableStateFlow(State())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var task: Job? = null
    private var archive: PackageInfo? = null
    private var pausedDuringInstall = false
    private var previousUpdate = 0L
    private var retried = false
    private var lastPath = AndroidAutoInstallPolicy.Path.SHELL

    private fun packageInfo(context: Context, name: String): PackageInfo? = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(
            name, if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else 0
        )
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    fun select(context: Context, uri: Uri) {
        if (state.value.pending || state.value.busy) return
        task?.cancel()
        task = scope.launch {
            state.value = State(busy = true, message = "Reading selected APK…")
            try {
                val app = context.applicationContext
                val file = AndroidAutoApkProvider.file(app)
                val length = withContext(Dispatchers.IO) {
                    check(file.parentFile?.mkdirs() == true || file.parentFile?.isDirectory == true)
                    val temp = java.io.File(file.parentFile, "selected.partial")
                    try {
                        var bytes = 0L
                        app.contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(temp).use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    bytes += read
                                    check(bytes <= 512L * 1024 * 1024) { "APK exceeds the 512 MB limit" }
                                    output.write(buffer, 0, read)
                                }
                            }
                        } ?: error("Unable to open selected APK")
                        check(bytes > 0) { "Selected APK was empty" }
                        check(temp.renameTo(file) || run {
                            file.delete()
                            temp.renameTo(file)
                        }) { "Could not stage the selected APK" }
                        bytes
                    } finally {
                        temp.delete()
                    }
                }
                val parsed = app.packageManager.getPackageArchiveInfo(file.absolutePath,
                    if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else 0)
                    ?: error("Invalid APK (split APK bundles are not supported)")
                check(parsed.packageName.matches(Regex("[A-Za-z0-9_.]+"))) {
                    "Invalid package name"
                }
                archive = parsed
                state.value = State(packageName = parsed.packageName,
                    version = parsed.versionName.orEmpty(), sizeBytes = length,
                    message = "Ready. Confirm installation to continue.",
                    details = "")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                archive = null
                state.value = State(message = "Could not load APK: " +
                    (failure.message ?: failure.javaClass.simpleName))
            }
        }
    }

    fun install(activity: Activity) {
        if (state.value.busy || state.value.pending || archive == null) return
        retried = false
        val shellReady = NativeShizukuManager.state.value.serviceReady && runCatching {
            Shizuku.pingBinder() && Shizuku.getUid() == Process.SHELL_UID
        }.getOrDefault(false)
        val preferred = activity.getSharedPreferences("android_auto_installer", Context.MODE_PRIVATE)
            .getBoolean("shell_works", true)
        val path = AndroidAutoInstallPolicy.firstPath(shellReady, preferred)
        launch(activity, path)
    }

    private fun compatibleSigner(newApk: PackageInfo, old: PackageInfo): Boolean {
        if (Build.VERSION.SDK_INT < 28) return true
        val installed = old.signingInfo ?: return true // let platform installer decide
        val target = newApk.signingInfo ?: return true
        val existing = installed.apkContentsSigners.orEmpty().toList()
        val allowed = (if (target.hasMultipleSigners()) target.apkContentsSigners
            else target.signingCertificateHistory).orEmpty().toList()
        return allowed.containsAll(existing)
    }

    private fun launch(activity: Activity, path: AndroidAutoInstallPolicy.Path) {
        task?.cancel()
        task = scope.launch {
            val staged = archive ?: return@launch
            val packageName = staged.packageName
            try {
                state.value = state.value.copy(busy = true, success = false,
                    initiatingPackage = null, installingPackage = null,
                    message = "Checking APK and installed version…")
                val old = packageInfo(activity, packageName)
                @Suppress("DEPRECATION")
                val oldCode = if (Build.VERSION.SDK_INT >= 28) old?.longVersionCode
                    else old?.versionCode?.toLong()
                @Suppress("DEPRECATION")
                val newCode = if (Build.VERSION.SDK_INT >= 28) staged.longVersionCode
                    else staged.versionCode.toLong()
                check(oldCode == null || newCode >= oldCode) {
                    "A newer version is already installed. Uninstall it first."
                }
                check(old == null || compatibleSigner(staged, old)) {
                    "The installed app has a different signing key. " +
                        "Uninstalling it would erase its data; no changes were made."
                }
                if ((path == AndroidAutoInstallPolicy.Path.APP || Build.VERSION.SDK_INT <= 34) &&
                    !activity.packageManager.canRequestPackageInstalls()) {
                    state.value = state.value.copy(busy = false, pending = false,
                        message = "Allow LocalAdb to install unknown apps, then tap Install again.")
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.packageName)))
                    return@launch
                }
                if (path == AndroidAutoInstallPolicy.Path.SHELL) {
                    check(NativeShizukuManager.state.value.serviceReady &&
                        Shizuku.pingBinder() && Shizuku.getUid() == Process.SHELL_UID) {
                        "Embedded Shizuku UID 2000 is not ready"
                    }
                }
                previousUpdate = old?.lastUpdateTime ?: 0
                lastPath = path
                pausedDuringInstall = false
                val file = AndroidAutoApkProvider.file(activity)
                check(file.exists()) { "The staged APK is no longer available" }
                val uri = AndroidAutoApkProvider.uri(activity)
                val target = if (Build.VERSION.SDK_INT >= 29 &&
                    runCatching { activity.packageManager.getPackageInfo(
                        "com.google.android.packageinstaller", 0) }.isSuccess)
                    AndroidAutoInstallPolicy.GOOGLE_INSTALLER
                    else AndroidAutoInstallPolicy.AOSP_INSTALLER
                for (pkg in arrayOf("com.google.android.packageinstaller",
                    "com.android.packageinstaller", AndroidAutoInstallPolicy.VENDING)) {
                    runCatching { activity.grantUriPermission(pkg, uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                }
                val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                    putExtra(Intent.EXTRA_RETURN_RESULT, true)
                    putExtra("android.intent.extra.INSTALLER_PACKAGE_NAME",
                        AndroidAutoInstallPolicy.VENDING)
                    putExtra("android.content.pm.extra.VERIFICATION_INSTALLER_PACKAGE",
                        AndroidAutoInstallPolicy.VENDING)
                    putExtra("android.intent.extra.INSTALL_REASON", 1)
                    putExtra(Intent.EXTRA_REFERRER_NAME,
                        "android-app://" + AndroidAutoInstallPolicy.VENDING)
                    putExtra(Intent.EXTRA_REFERRER,
                        Uri.parse("android-app://" + AndroidAutoInstallPolicy.VENDING))
                }
                state.value = state.value.copy(busy = true, pending = true,
                    installMethod = path.name,
                    message = "Confirm the system installer prompt…",
                    details = "")
                if (path == AndroidAutoInstallPolicy.Path.SHELL) {
                    intent.component = ComponentName.unflattenFromString(target)
                    val result = withContext(Dispatchers.IO) {
                        startWithLocalHelper(activity, intent)
                    }
                    check(result == "ok") { "System installer launch failed: " + result }
                } else {
                    activity.startActivity(intent)
                }
                state.value = state.value.copy(busy = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                state.value = state.value.copy(busy = false, pending = false,
                    message = "Installation could not start: " +
                        (failure.message ?: failure.javaClass.simpleName))
            }
        }
    }

    internal suspend fun startWithLocalHelper(activity: Activity, intent: Intent): String {
        val args = Shizuku.UserServiceArgs(ComponentName(activity, LocalHelperService::class.java))
            .tag(if (intent.data?.authority == activity.packageName + ".apk-install")
                "localhelper-apk-installer" else "localhelper-android-auto-installer")
            .version(2)
            .processNameSuffix("android_auto_installer")
            .daemon(false)
        val attached = CompletableDeferred<IBinder>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service != null && service.pingBinder()) attached.complete(service)
                else attached.completeExceptionally(IllegalStateException("Helper Binder unavailable"))
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                attached.completeExceptionally(IllegalStateException("Helper disconnected"))
            }
        }
        Shizuku.bindUserService(args, conn)
        try {
            val binder = withTimeout(15_000) { attached.await() }
            val request = Parcel.obtain()
            val response = Parcel.obtain()
            try {
                request.writeInterfaceToken(LocalHelperService.DESCRIPTOR)
                intent.writeToParcel(request, 0)
                request.writeStrongBinder(activity.window.attributes.token)
                check(binder.transact(LocalHelperService.INSTALL_ANDROID_AUTO, request, response, 0)) {
                    "Helper rejected installer transaction"
                }
                response.readException()
                return response.readString().orEmpty()
            } finally {
                response.recycle()
                request.recycle()
            }
        } finally {
            withContext(NonCancellable) {
                runCatching { Shizuku.unbindUserService(args, conn, true) }
            }
        }
    }

    fun onPause() {
        if (state.value.pending) pausedDuringInstall = true
    }

    fun onResume(activity: Activity) {
        if (!state.value.pending || !pausedDuringInstall) return
        pausedDuringInstall = false
        scope.launch {
            delay(800) // allow PackageManager's recorded source to settle
            verify(activity)
        }
    }

    private fun verify(activity: Activity) {
        val name = state.value.packageName ?: return
        val now = packageInfo(activity, name)
        if (now == null || now.lastUpdateTime <= previousUpdate) {
            state.value = state.value.copy(pending = false, busy = false,
                message = "Installation was cancelled or rejected. No new installation was detected.")
            return
        }
        val initiator: String?
        val installing: String?
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val source = activity.packageManager.getInstallSourceInfo(name)
                initiator = source.initiatingPackageName
                installing = source.installingPackageName
            } else {
                initiator = null
                @Suppress("DEPRECATION")
                installing = activity.packageManager.getInstallerPackageName(name)
            }
        } catch (failure: Exception) {
            state.value = state.value.copy(pending = false, busy = false,
                message = "Installed, but install-source verification failed: " + failure.message)
            return
        }
        val accepted = AndroidAutoInstallPolicy.accepts(
            initiator, installing, Build.VERSION.SDK_INT)
        if (accepted) {
            activity.getSharedPreferences("android_auto_installer", Context.MODE_PRIVATE)
                .edit().putBoolean("shell_works",
                    lastPath == AndroidAutoInstallPolicy.Path.SHELL).apply()
            state.value = state.value.copy(pending = false, success = true,
                initiatingPackage = initiator, installingPackage = installing,
                message = "Installed with accepted Google Play attribution.",
                details = "")
            return
        }
        if (AndroidAutoInstallPolicy.shouldRetry(true, accepted, retried)) {
            retried = true
            val alternate = if (lastPath == AndroidAutoInstallPolicy.Path.SHELL)
                AndroidAutoInstallPolicy.Path.APP else AndroidAutoInstallPolicy.Path.SHELL
            val shellReady = NativeShizukuManager.state.value.serviceReady && runCatching {
                Shizuku.pingBinder() && Shizuku.getUid() == Process.SHELL_UID
            }.getOrDefault(false)
            if (alternate != AndroidAutoInstallPolicy.Path.SHELL || shellReady) {
                state.value = state.value.copy(pending = false, busy = false,
                    initiatingPackage = initiator, installingPackage = installing,
                    message = "Attribution was rejected; retrying the alternative installer…",
                    details = "")
                launch(activity, alternate)
                return
            }
        }
        state.value = state.value.copy(pending = false, busy = false,
            initiatingPackage = initiator, installingPackage = installing,
            message = "APK installed, but Google Play attribution was not accepted.",
            details = "")
    }

    fun shutdown(context: Context) {
        task?.cancel()
        task = null
        pausedDuringInstall = false
        archive = null
        state.value = State()
        runCatching { AndroidAutoApkProvider.file(context).delete() }
        runCatching {
            for (pkg in arrayOf("com.google.android.packageinstaller",
                "com.android.packageinstaller", AndroidAutoInstallPolicy.VENDING)) {
                context.revokeUriPermission(AndroidAutoApkProvider.uri(context),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }
}

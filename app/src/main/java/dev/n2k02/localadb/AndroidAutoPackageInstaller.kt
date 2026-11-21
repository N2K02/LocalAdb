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
import android.os.ParcelFileDescriptor
import android.provider.Settings
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
    enum class ResultStatus { IDLE, INSTALLING, INSTALLED, FAILED, NOT_VERIFIED, CANCELLED }

    data class State(
        val packageName: String? = null,
        val version: String = "",
        val sizeBytes: Long = 0L,
        val busy: Boolean = false,
        val pending: Boolean = false,
        val success: Boolean = false,
        val resultStatus: ResultStatus = ResultStatus.IDLE,
        val message: String = "Select a compatible Android Auto APK to install.",
        // Structured read-back is independent of the free-form error message.
        val initiatingPackage: String? = null,
        val installingPackage: String? = null,
        val requestedInstaller: String? = null,
        val attributionStatus: String = "",
        val installMethod: String? = null,
        val installerComponent: String? = null,
        val details: String = "",
    )
    val state = MutableStateFlow(State())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var task: Job? = null
    private var archive: PackageInfo? = null
    private var pausedDuringInstall = false
    private var verification: Job? = null
    private var attempt: InstallAttempt? = null

    private data class InstallAttempt(
        val path: AndroidAutoInstallPolicy.Path,
        val packageName: String,
        val version: Long,
        val previousUpdate: Long,
        val component: ComponentName,
        val verifiedInstallerPackages: Set<String>,
    )

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo?): Long? = info?.let {
        if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong()
    }

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
                    message = "APK selected. Choose an installation method.",
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

    fun installViaShizuku(activity: Activity) =
        launch(activity, AndroidAutoInstallPolicy.Path.SHELL)

    fun installWithSystem(activity: Activity, openInstaller: (Intent) -> Unit) =
        launch(activity, AndroidAutoInstallPolicy.Path.APP, openInstaller)

    /**
     * Shared safety preflight for the Home Shizuku installer and direct APK flow.
     * Reject known incompatible updates before a privileged shell install.
     * Android's package manager remains the final signature authority.
     */
    internal fun validateInstallCompatibility(context: Context, apk: java.io.File) {
        check(apk.isFile && apk.length() in 1..ApkInstallPolicy.MAX_APK_BYTES) {
            "Selected APK is missing or exceeds the size limit"
        }
        @Suppress("DEPRECATION")
        val parsed = context.packageManager.getPackageArchiveInfo(
            apk.absolutePath,
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else 0
        ) ?: error("Invalid APK archive")
        val old = packageInfo(context, parsed.packageName) ?: return
        @Suppress("DEPRECATION")
        val oldCode = if (Build.VERSION.SDK_INT >= 28) old.longVersionCode
            else old.versionCode.toLong()
        @Suppress("DEPRECATION")
        val newCode = if (Build.VERSION.SDK_INT >= 28) parsed.longVersionCode
            else parsed.versionCode.toLong()
        check(newCode >= oldCode) {
            "A newer version is already installed. Downgrade was not requested."
        }
        check(compatibleSigner(parsed, old)) {
            "Installed app uses a different signing certificate; no changes were made."
        }
    }

    /**
     * Home Shizuku installer: direct, confirmation-free APK session under UID 2000.
     * Never claims Google Play attribution; the separate native path retains
     * its existing Android Auto/Play compatibility behavior.
     */
    fun installDirectFromHome(activity: Activity) {
        if (state.value.busy || state.value.pending) return
        val selected = archive ?: return
        if (!(NativeShizukuManager.state.value.serviceReady && runCatching {
                Shizuku.pingBinder() && Shizuku.getUid() == Process.SHELL_UID
            }.getOrDefault(false))) {
            state.value = state.value.copy(
                resultStatus = ResultStatus.FAILED,
                message = "Direct install requires the embedded Shizuku shell (UID 2000). " +
                    "Start Shizuku or select Native install.",
                details = "Direct shell service unavailable")
            return
        }
        task?.cancel()
        task = scope.launch {
            val app = activity.applicationContext
            val file = AndroidAutoApkProvider.file(app)
            try {
                state.value = state.value.copy(
                    busy = true, pending = false, success = false,
                    resultStatus = ResultStatus.INSTALLING,
                    installMethod = "DIRECT_ADB", installerComponent = null,
                    initiatingPackage = null, installingPackage = null,
                    requestedInstaller = null, attributionStatus = "",
                    message = "Installing selected APK with Shizuku shell…",
                    details = "")
                val requestedInstaller = withContext(Dispatchers.IO) {
                    validateInstallCompatibility(app, file)
                    DirectInstallAttribution.requestedInstaller(app)
                }
                val old = packageInfo(app, selected.packageName)
                val oldUpdate = old?.lastUpdateTime ?: 0L
                appLog.append("Home APK installer: direct session started for " + selected.packageName +
                    "; Play installer-of-record requested=" + (requestedInstaller != null))
                val result = withContext(Dispatchers.IO) { installDirect(app, file) }
                check(result == "Success") { "Package manager: $result" }
                val current = packageInfo(app, selected.packageName)
                val changed = current != null && (old == null || current.lastUpdateTime > oldUpdate)
                val source = DirectInstallAttribution.readBack(app, selected.packageName)
                val verdict = DirectInstallAttribution.verdict(requestedInstaller, source)
                state.value = state.value.copy(
                    busy = false, pending = false, success = changed,
                    resultStatus = if (changed) ResultStatus.INSTALLED else ResultStatus.NOT_VERIFIED,
                    requestedInstaller = requestedInstaller,
                    initiatingPackage = source.initiating,
                    installingPackage = source.installing,
                    attributionStatus = verdict,
                    message = if (changed) "Installed using ADB shell. $verdict"
                        else "Package manager reported success; installed version could not be verified. $verdict",
                    details = DirectInstallAttribution.sourceDetails(requestedInstaller, source)
                )
                appLog.append("Home APK installer: direct commit succeeded; read-back=" + changed +
                    "; installing=" + source.installing + "; initiating=" + source.initiating)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                appLog.append("Home APK installer: direct failed (" +
                    failure.javaClass.simpleName + "): " +
                    (failure.message ?: "unknown").take(220))
                state.value = state.value.copy(
                    busy = false, pending = false, success = false,
                    resultStatus = ResultStatus.FAILED,
                    message = "Direct installation failed: " +
                        (failure.message ?: failure.javaClass.simpleName).take(250),
                    details = "Native install remains available.")
            }
        }
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

    private fun launch(
        activity: Activity,
        path: AndroidAutoInstallPolicy.Path,
        openInstaller: ((Intent) -> Unit)? = null,
    ) {
        if (state.value.busy || state.value.pending || archive == null) return
        task?.cancel()
        task = scope.launch {
            val staged = archive ?: return@launch
            try {
                state.value = state.value.copy(
                    busy = true, success = false, resultStatus = ResultStatus.INSTALLING,
                    initiatingPackage = null, installingPackage = null,
                    requestedInstaller = null, attributionStatus = "",
                    installMethod = path.name, installerComponent = null,
                    message = "Checking APK and installed version…", details = "",
                )
                if (path == AndroidAutoInstallPolicy.Path.SHELL) {
                    check(NativeShizukuManager.state.value.serviceReady &&
                        Shizuku.pingBinder() && Shizuku.getUid() == Process.SHELL_UID) {
                        "Start embedded Shizuku, then retry."
                    }
                }
                if ((path == AndroidAutoInstallPolicy.Path.APP || Build.VERSION.SDK_INT <= 34) &&
                    !activity.packageManager.canRequestPackageInstalls()) {
                    state.value = state.value.copy(busy = false, resultStatus = ResultStatus.IDLE,
                        message = "Allow LocalADB to install unknown apps, then retry.")
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.packageName)))
                    return@launch
                }
                val file = AndroidAutoApkProvider.file(activity)
                validateInstallCompatibility(activity, file)
                val previous = packageInfo(activity, staged.packageName)
                val uri = AndroidAutoApkProvider.uri(activity)
                val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                    setDataAndType(uri, AndroidAutoInstallPolicy.APK_MIME_TYPE)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val handlers = findSystemInstallers(activity, intent)
                val target = handlers.firstOrNull() ?: error("No verified Android package installer found")
                val session = InstallAttempt(path, staged.packageName, requireNotNull(versionCode(staged)),
                    previous?.lastUpdateTime ?: 0, target, handlers.map { it.packageName }.toSet())
                attempt = session
                pausedDuringInstall = false
                activity.grantUriPermission(target.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent.component = target
                intent.putExtra(Intent.EXTRA_RETURN_RESULT, path == AndroidAutoInstallPolicy.Path.APP)
                if (path == AndroidAutoInstallPolicy.Path.SHELL) {
                    activity.grantUriPermission("com.android.shell", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                state.value = state.value.copy(pending = true,
                    installerComponent = target.flattenToShortString(),
                    message = "Confirm the Android package installer prompt…")
                appLog.append("AA install: ${path.name} opening ${target.flattenToShortString()}")
                if (path == AndroidAutoInstallPolicy.Path.SHELL) {
                    val result = withContext(Dispatchers.IO) { startWithLocalHelper(activity, intent) }
                    check(result == "ok") { result.ifBlank { "Shizuku did not open the Android installer" } }
                    if (attempt === session && !pausedDuringInstall && verification?.isActive != true) {
                        // Covers installers that return without ever pausing LocalADB.
                        verifyAfterLaunch(activity, session, resultCode = null, initialDelay = 10_000)
                    }
                } else {
                    requireNotNull(openInstaller)(intent)
                }
                state.value = state.value.copy(busy = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                clearAttempt(activity)
                val reason = failure.message ?: failure.javaClass.simpleName
                appLog.append("AA install ${path.name} launch failed: ${reason.take(250)}")
                state.value = state.value.copy(busy = false, pending = false,
                    resultStatus = ResultStatus.FAILED,
                    message = "Could not open Android installer. ${reason.take(180)}",
                    details = reason.takeIf { it.length > 180 }.orEmpty())
            }
        }
    }

    private fun findSystemInstallers(context: Context, intent: Intent): List<ComponentName> {
        @Suppress("DEPRECATION")
        return context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_SYSTEM_ONLY)
            .mapNotNull { result ->
                val info = result.activityInfo ?: return@mapNotNull null
                if (!info.exported || !info.enabled || info.packageName == context.packageName) null
                else ComponentName(info.packageName, info.name)
            }
            .distinct()
            .sortedBy { when (it.packageName) {
                "com.google.android.packageinstaller" -> 0
                "com.android.packageinstaller" -> 1
                else -> 2
            } }
    }

    /** Installs one private, validated APK over a read-only descriptor using
     * the existing Shizuku UID 2000 helper. Keeps the work off the UI thread.
     */
    /** AIDL UserService installer based on the upstream Shizuku demo.
     * The service uses Android package install sessions under shell UID.
     */
    internal suspend fun installDirect(context: Context, file: java.io.File, onStage: (String) -> Unit = {}): String {
        check(file.isFile && file.length() in 1..ApkInstallPolicy.MAX_APK_BYTES) {
            "Selected APK is missing or too large"
        }
        check(Shizuku.pingBinder() && Shizuku.getUid() == Process.SHELL_UID) {
            "ADB shell authorization unavailable"
        }
        val requestedInstaller = DirectInstallAttribution.requestedInstaller(context)
        val args = Shizuku.UserServiceArgs(ComponentName(context, DirectApkInstallService::class.java))
            .tag("direct-apk-aidl-v3")
            .version(5)
            .processNameSuffix("apk_aidl_v3")
            .daemon(false)
        val attached = CompletableDeferred<IBinder>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service != null && service.pingBinder()) attached.complete(service)
                else attached.completeExceptionally(IllegalStateException("Direct installer service unavailable"))
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                attached.completeExceptionally(IllegalStateException("Direct installer disconnected"))
            }
        }
        Shizuku.bindUserService(args, conn)
        try {
            val binder = withTimeout(15_000) { attached.await() }
            val installer = IDirectApkInstallService.Stub.asInterface(binder)
                ?: error("Direct installer AIDL service unavailable")
            check(installer.serviceUid == Process.SHELL_UID) {
                "Direct installer is not running as shell"
            }
            val callback = object : IInstallProgressCallback.Stub() {
                override fun onStage(stage: String?) {
                    if (!stage.isNullOrBlank()) onStage(stage.take(160))
                }
            }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                installer.installApk(fd, file.length(), requestedInstaller.orEmpty(), callback)
            }
        } finally {
            withContext(NonCancellable) {
                runCatching { Shizuku.unbindUserService(args, conn, true) }
            }
        }
    }

    internal suspend fun startWithLocalHelper(activity: Activity, intent: Intent): String {
        val args = Shizuku.UserServiceArgs(ComponentName(activity, LocalHelperService::class.java))
            .tag("localhelper-android-auto-installer")
            .version(3)
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
        if (attempt?.path != AndroidAutoInstallPolicy.Path.SHELL) return
        pausedDuringInstall = true
        verification?.cancel()
        verification = null
    }

    fun onResume(context: Context) {
        val session = attempt ?: return
        if (session.path != AndroidAutoInstallPolicy.Path.SHELL || !pausedDuringInstall) return
        pausedDuringInstall = false
        verifyAfterLaunch(context, session, resultCode = null)
    }

    fun onInstallerResult(context: Context, resultCode: Int) {
        val session = attempt ?: return
        if (session.path == AndroidAutoInstallPolicy.Path.APP) {
            verifyAfterLaunch(context, session, resultCode)
        }
    }

    private fun verifyAfterLaunch(
        context: Context, session: InstallAttempt, resultCode: Int?, initialDelay: Long = 0,
    ) {
        verification?.cancel()
        val app = context.applicationContext
        verification = scope.launch {
            delay(initialDelay)
            val delays = listOf(0L, 800L, 1200L, 2000L, 3500L)
            for ((index, wait) in delays.withIndex()) {
                delay(wait)
                if (attempt !== session) return@launch
                val now = packageInfo(app, session.packageName)
                val installed = AndroidAutoInstallPolicy.isSelectedInstall(
                    versionCode(now), session.version, now?.lastUpdateTime ?: 0, session.previousUpdate,
                    resultCode == Activity.RESULT_OK,
                )
                val lastCheck = index == delays.lastIndex
                if (installed) {
                    val source = DirectInstallAttribution.readBack(app, session.packageName)
                    val sourceComplete = source.available && source.installing != null &&
                        (Build.VERSION.SDK_INT < 30 || source.initiating != null)
                    if (sourceComplete || lastCheck) {
                        finishAttempt(app, session, source, sourceComplete)
                        return@launch
                    }
                } else if (lastCheck) {
                    val (status, message) = when (resultCode) {
                        Activity.RESULT_CANCELED -> ResultStatus.CANCELLED to "Android returned a cancelled result."
                        Activity.RESULT_FIRST_USER -> ResultStatus.FAILED to "Android reported that the installation failed."
                        else -> ResultStatus.NOT_VERIFIED to "No new installation of the selected APK could be verified."
                    }
                    revokeGrant(app, session)
                    attempt = null
                    state.value = state.value.copy(busy = false, pending = false,
                        resultStatus = status, message = message)
                }
            }
        }
    }

    private fun finishAttempt(
        context: Context, session: InstallAttempt, source: DirectInstallAttribution.Source, complete: Boolean,
    ) {
        val matches = AndroidAutoInstallPolicy.accepts(
            source.initiating, source.installing, Build.VERSION.SDK_INT, session.verifiedInstallerPackages,
        )
        val message = when {
            !complete -> "Installed. Android did not provide complete install-source information."
            matches -> "Installed with recognized source metadata. Check the Android Auto launcher."
            else -> "Installed. The source metadata differs from the expected Android Auto test setup."
        }
        revokeGrant(context, session)
        attempt = null
        state.value = state.value.copy(
            busy = false, pending = false, success = true, resultStatus = ResultStatus.INSTALLED,
            initiatingPackage = source.initiating, installingPackage = source.installing,
            message = message, details = source.failure.orEmpty(),
        )
        appLog.append("AA install ${session.path.name}: initiator=${source.initiating}, installer=${source.installing}")
    }

    private fun clearAttempt(context: Context) {
        verification?.cancel()
        verification = null
        attempt?.let { revokeGrant(context, it) }
        attempt = null
        pausedDuringInstall = false
    }

    private fun revokeGrant(context: Context, session: InstallAttempt) {
        runCatching {
            context.revokeUriPermission(AndroidAutoApkProvider.uri(context), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.onFailure { appLog.append("AA APK grant cleanup failed for ${session.component.packageName}") }
    }

    /** Discard a selected APK without stopping the Shizuku service.
     * Only the idle selection can be cleared; a running install must finish.
     */
    fun cancelSelection(context: Context) {
        if (state.value.busy || state.value.pending) return
        if (archive == null && state.value.packageName == null) return
        shutdown(context)
        state.value = State(message = "Selection cancelled. Choose an APK to continue.")
    }

    fun shutdown(context: Context) {
        task?.cancel()
        task = null
        clearAttempt(context)
        archive = null
        state.value = State()
        runCatching { AndroidAutoApkProvider.file(context).delete() }
    }
}

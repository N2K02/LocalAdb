package dev.n2k02.localadb

import android.app.Application
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import rikka.shizuku.Shizuku
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

internal data class ApkInstallUiState(
    val loading: Boolean = true,
    val name: String = "",
    val packageName: String = "",
    val version: String = "",
    val size: Long = 0,
    val message: String = "",
    val isError: Boolean = false,
    val phase: Phase = Phase.STAGING,
) {
    enum class Phase { STAGING, READY, PERMISSION, INSTALLING, RESULT, ERROR }
}

/** Retained across configuration changes; each invocation stages its own immutable APK. */
internal class ApkInstallViewModel(application: Application) : AndroidViewModel(application) {
    val state = MutableStateFlow(ApkInstallUiState())
    val session: String = UUID.randomUUID().toString()
    private val app = getApplication<Application>()
    private var started = false
    private var priorVersion: Long? = null
    private var priorUpdate: Long? = null
    private var pendingCleanup = false

    fun start(input: Uri?) {
        if (started) return
        started = true
        if (input == null || input.scheme != "content") {
            state.value = ApkInstallUiState(loading = false, phase = ApkInstallUiState.Phase.ERROR,
                message = "Select an APK from a file manager")
            return
        }
        viewModelScope.launch {
            try {
                val (info, bytes) = withContext(Dispatchers.IO) {
                    ApkInstallProvider.cleanupExpired(app)
                    val output = ApkInstallProvider.file(app, session)
                    require(output.parentFile?.mkdirs() == true || output.parentFile?.isDirectory == true)
                    val partial = File(output.parentFile, "$session.partial")
                    try {
                        var count = 0L
                        app.contentResolver.openInputStream(input)?.use { stream ->
                            FileOutputStream(partial).use { out ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    val n = stream.read(buffer)
                                    if (n == -1) break
                                    count += n
                                    require(count <= ApkInstallPolicy.MAX_APK_BYTES) { "APK exceeds 512 MB" }
                                    out.write(buffer, 0, n)
                                }
                            }
                        } ?: error("Unable to read selected APK")
                        require(count > 0) { "APK is empty" }
                        val parsed = app.packageManager.getPackageArchiveInfo(
                            partial.absolutePath, PackageManager.GET_ACTIVITIES)
                            ?: error("Invalid APK or unsupported split APK")
                        require(!output.exists()) { "APK session already exists" }
                        require(partial.renameTo(output)) { "Could not stage APK" }
                        parsed to count
                    } finally {
                        partial.delete()
                    }
                }
                val old = runCatching {
                    app.packageManager.getPackageInfo(info.packageName, 0)
                }.getOrNull()
                priorVersion = old?.let(::versionCode)
                priorUpdate = old?.lastUpdateTime
                val name = info.applicationInfo?.let {
                    val archive = ApkInstallProvider.file(app, session).absolutePath
                    it.sourceDir = archive
                    it.publicSourceDir = archive
                    runCatching { app.packageManager.getApplicationLabel(it).toString() }.getOrNull()
                } ?: info.packageName
                state.value = ApkInstallUiState(false, name, info.packageName,
                    info.versionName.orEmpty(), bytes, phase = ApkInstallUiState.Phase.READY)
                appLog.append("APK installer: validated ${info.packageName}")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                appLog.append("APK installer: validation failure (${failure.javaClass.simpleName})")
                state.value = ApkInstallUiState(loading = false,
                    message = failure.message ?: "Unable to stage APK",
                    phase = ApkInstallUiState.Phase.ERROR)
            }
        }
    }

    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }

    /** Primary Install button: perform a real ADB-shell package installation,
     * without opening the system installer. No automatic privileged fallback.
     */
    fun installWithAdb() {
        if (state.value.phase != ApkInstallUiState.Phase.READY) return
        val ready = NativeShizukuManager.state.value.serviceReady && runCatching {
            Shizuku.pingBinder() && Shizuku.getUid() == Process.SHELL_UID
        }.getOrDefault(false)
        if (!ready) {
            resetReady("ADB is not connected. Connect LocalADB or choose Native install.", error = true)
            appLog.append("APK installer: ADB shell not ready")
            return
        }
        installing()
        viewModelScope.launch {
            try {
                appLog.append("APK installer: streaming APK to ADB package manager")
                val result = withContext(Dispatchers.IO) {
                    val staged = ApkInstallProvider.file(app, session)
                    AndroidAutoPackageInstaller.validateInstallCompatibility(app, staged)
                    AndroidAutoPackageInstaller.installDirect(app, staged)
                }
                check(result == "Success") { "Package manager: $result" }
                if (installed()) {
                    complete(resultSuccess = true, cancelled = false)
                } else {
                    resetReady("ADB returned success, but the installed package could not be verified.", error = true)
                    appLog.append("APK installer: ADB result unverified")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                appLog.append("APK installer: ADB install failed (${failure.javaClass.simpleName})")
                resetReady("ADB installation failed: ${failure.message ?: "unknown error"}. Try Native install.", error = true)
            }
        }
    }

    fun stagedUri() = ApkInstallProvider.uri(app, session)

    fun installing() {
        if (state.value.phase != ApkInstallUiState.Phase.READY &&
            state.value.phase != ApkInstallUiState.Phase.PERMISSION) return
        state.value = state.value.copy(phase = ApkInstallUiState.Phase.INSTALLING,
            message = "", isError = false)
    }

    fun permissionRequired() {
        state.value = state.value.copy(phase = ApkInstallUiState.Phase.PERMISSION)
        appLog.append("APK installer: install-source permission required")
    }

    fun resetReady(message: String, error: Boolean = false) {
        state.value = state.value.copy(phase = ApkInstallUiState.Phase.READY, message = message,
            isError = error)
    }

    fun installed(): Boolean {
        val pkg = state.value.packageName
        if (pkg.isBlank()) return false
        val current = runCatching { app.packageManager.getPackageInfo(pkg, 0) }.getOrNull()
            ?: return false
        return ApkInstallPolicy.installedChanged(priorVersion, priorUpdate,
            versionCode(current), current.lastUpdateTime)
    }

    fun complete(resultSuccess: Boolean, cancelled: Boolean) {
        val text = ApkInstallPolicy.result(resultSuccess, installed(), cancelled)
        state.value = state.value.copy(phase = ApkInstallUiState.Phase.RESULT, message = text)
        appLog.append("APK installer: $text")
        pendingCleanup = true
        // The installer has returned; no further content reads are expected.
        app.revokeUriPermission(stagedUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ApkInstallProvider.cleanup(app, session)
    }

    fun abandon() {
        if (state.value.phase == ApkInstallUiState.Phase.INSTALLING) return
        app.revokeUriPermission(stagedUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ApkInstallProvider.cleanup(app, session)
    }

    override fun onCleared() {
        // Do not remove content while a system installer may still be reading it.
        if (state.value.phase != ApkInstallUiState.Phase.INSTALLING && !pendingCleanup) abandon()
        super.onCleared()
    }
}

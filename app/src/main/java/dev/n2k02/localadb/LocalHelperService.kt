package dev.n2k02.localadb

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.app.ActivityManager
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import java.lang.reflect.InvocationTargetException
import dev.n2k02.localadb.AndroidAutoInstallPolicy.LaunchResult
import dev.n2k02.localadb.AndroidAutoInstallPolicy.LaunchStatus

/** Instantiated by Shizuku in a separate shell process, never an Android Service. */
class LocalHelperService : Binder() {
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == DESTROY) {
            // Shizuku's documented destroy transaction; only kills this test process.
            kotlin.system.exitProcess(0)
        }
        if (code == PING) {
            data.enforceInterface(DESCRIPTOR)
            val nonce = data.readString()
            checkNotNull(reply).apply {
                writeNoException()
                writeInt(Process.myUid())
                writeString(nonce)
            }
            return true
        }
        if (code == INSTALL_ANDROID_AUTO) {
            data.enforceInterface(DESCRIPTOR)
            val intent = Intent.CREATOR.createFromParcel(data)
            val resultTo = data.readStrongBinder()
            val result = startSystemInstaller(intent, resultTo)
            checkNotNull(reply).apply {
                writeNoException()
                writeString(result.status.name)
                writeString(result.detail)
            }
            return true
        }
        return super.onTransact(code, data, reply, flags)
    }

    /**
     * Shizuku starts this user service as UID 2000. Launch the *system* package
     * installer; do not call pm install (which records shell as initiator).
     * Only verified system installer components and an isolated APK session URI
     * are accepted; this Binder cannot run arbitrary shell input.
     */
    private fun startSystemInstaller(request: Intent, resultTo: IBinder?): LaunchResult {
        check(Process.myUid() == Process.SHELL_UID) { "Installer helper is not UID 2000" }
        check(request.action == Intent.ACTION_INSTALL_PACKAGE) { "Invalid install action" }
        val component = requireNotNull(request.component) { "No installer component selected" }
        val uri = request.dataString.orEmpty()
        check(AndroidAutoInstallPolicy.sessionFromUri(uri, "dev.n2k02.localadb.android-auto-apk") != null &&
            request.type == AndroidAutoInstallPolicy.APK_MIME_TYPE) {
            "Unexpected installation URI or type"
        }
        check(request.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) {
            "Installer requires a read-only APK grant"
        }
        val resolver = runSystemCommand(listOf(
            "/system/bin/cmd", "package", "query-activities", "--brief", "--components",
            "--query-flags", PackageManager.MATCH_SYSTEM_ONLY.toString(),
            "-a", AndroidAutoInstallPolicy.INSTALL_ACTION, "-d", uri,
            "-t", AndroidAutoInstallPolicy.APK_MIME_TYPE,
        ))
        if (!resolver.first || resolver.second.lineSequence().none {
            ComponentName.unflattenFromString(it.trim()) == component
        }) return LaunchResult(LaunchStatus.REJECTED, "Could not verify the selected system installer")
        val command = AndroidAutoInstallPolicy.shellLaunchArgs(component.flattenToString(), uri)
        val intent = Intent(AndroidAutoInstallPolicy.INSTALL_ACTION).apply {
            setDataAndType(request.data, AndroidAutoInstallPolicy.APK_MIME_TYPE)
            setComponent(component)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, AndroidAutoInstallPolicy.VENDING)
            putExtra("android.content.pm.extra.INSTALL_REASON", 1)
            putExtra(Intent.EXTRA_REFERRER_NAME, "android-app://" + AndroidAutoInstallPolicy.VENDING)
        }
        // Never invoke a second installer launcher after Binder was called: an
        // ambiguous Binder result can still leave a live confirmation window.
        var binderLaunchAttempted = false
        val binderResult = runCatching {
            val service = if (Build.VERSION.SDK_INT >= 29)
                Class.forName("android.app.ActivityTaskManager")
                    .getMethod("getService").invoke(null)
            else ActivityManager::class.java.getMethod("getService").invoke(null)
            val modern = Build.VERSION.SDK_INT >= 35
            val method = service.javaClass.methods.firstOrNull { candidate ->
                val parameters = candidate.parameterTypes
                if (modern) {
                    candidate.name == "startActivityAsUser" && parameters.size == 12 &&
                        parameters[3] == Intent::class.java
                } else {
                    candidate.name == "startActivity" &&
                        ((parameters.size == 11 && parameters[3] == Intent::class.java) ||
                            (parameters.size == 10 && parameters[2] == Intent::class.java))
                }
            } ?: error("No supported system installer launch signature")
            val args = if (modern) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // ActivityManager resolves this to the active Android user.
                arrayOf(null, "com.android.shell", null, intent, intent.type,
                    null, null, -1, 0, null, null, USER_CURRENT)
            } else {
                checkNotNull(resultTo) { "Missing requesting activity token" }
                if (method.parameterTypes.size == 11)
                    arrayOf(null, "com.android.shell", null, intent, intent.type,
                        resultTo, null, 1720, 0, null, null)
                else arrayOf(null, "com.android.shell", intent, intent.type,
                    resultTo, null, 1720, 0, null, null)
            }
            binderLaunchAttempted = true
            val result = method.invoke(service, *args) as Int
            LaunchResult(AndroidAutoInstallPolicy.binderLaunchStatus(result), "Binder start result $result")
        }.getOrElse { failure ->
            val cause = (failure as? InvocationTargetException)?.targetException ?: failure
            val status = if (failure !is InvocationTargetException || cause is SecurityException)
                LaunchStatus.REJECTED else LaunchStatus.UNKNOWN
            LaunchResult(status, "Binder installer error: " + (cause.message ?: cause.javaClass.simpleName))
        }
        if (binderLaunchAttempted || binderResult.status != LaunchStatus.REJECTED) return binderResult
        return try {
            val (success, output) = runSystemCommand(command)
            LaunchResult(AndroidAutoInstallPolicy.shellLaunchStatus(success, output),
                "Shell: ${output.trim().take(250)}. ${binderResult.detail}")
        } catch (failure: java.io.IOException) {
            LaunchResult(LaunchStatus.REJECTED, "Could not start shell installer: " + failure.message)
        }
    }

    private fun runSystemCommand(args: List<String>): Pair<Boolean, String> {
        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        // Drain output while the command runs: waiting first can deadlock if the
        // package manager writes more than the OS pipe buffer.
        val output = StringBuilder()
        val reader = kotlin.concurrent.thread(name = "installer-command-output", isDaemon = true) {
            try {
                process.inputStream.bufferedReader().use { stream ->
                    val buffer = CharArray(1024)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        synchronized(output) {
                            val remaining = 4096 - output.length
                            if (remaining > 0) output.append(buffer, 0, minOf(count, remaining))
                        }
                    }
                }
            } catch (_: java.io.IOException) {
                // Process termination also closes the stream on some devices.
            }
        }
        val finished = try {
            process.waitFor(12, java.util.concurrent.TimeUnit.SECONDS)
        } catch (interrupted: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            return false to "Command interrupted"
        }
        if (!finished) process.destroyForcibly()
        reader.join(500)
        if (!finished) return false to "Command timed out"
        return (process.exitValue() == 0) to synchronized(output) { output.toString() }
    }

    companion object {
        private const val USER_CURRENT = -2
        const val DESCRIPTOR = "dev.n2k02.localadb.LocalHelperService"
        const val PING = IBinder.FIRST_CALL_TRANSACTION
        const val INSTALL_ANDROID_AUTO = IBinder.FIRST_CALL_TRANSACTION + 1
        const val DESTROY = 16777115
    }
}

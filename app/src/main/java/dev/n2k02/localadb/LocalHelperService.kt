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
            checkNotNull(reply).apply {
                writeNoException()
                writeString(startSystemInstaller(intent, resultTo))
            }
            return true
        }
        return super.onTransact(code, data, reply, flags)
    }

    /**
     * Shizuku starts this user service as UID 2000. Launch the *system* package
     * installer; do not call pm install (which records shell as initiator).
     * Only verified system installer components and our fixed APK URI
     * are accepted; this Binder cannot run arbitrary shell input.
     */
    private fun startSystemInstaller(request: Intent, resultTo: IBinder?): String {
        check(Process.myUid() == Process.SHELL_UID) { "Installer helper is not UID 2000" }
        check(request.action == Intent.ACTION_INSTALL_PACKAGE) { "Invalid install action" }
        val component = requireNotNull(request.component) { "No installer component selected" }
        val uri = request.dataString.orEmpty()
        val expected = "content://dev.n2k02.localadb.android-auto-apk/selected.apk"
        check(uri == expected && request.type == AndroidAutoInstallPolicy.APK_MIME_TYPE) {
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
        check(resolver.first && resolver.second.lineSequence().any {
            ComponentName.unflattenFromString(it.trim()) == component
        }) { "Selected activity is not a verified system package installer" }
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
            val result = method.invoke(service, *args) as Int
            if (result >= 0) "ok" else "System installer start failed: " + result
        }.getOrElse { failure ->
            val cause = (failure as? InvocationTargetException)?.targetException ?: failure
            "Binder installer error: " + (cause.message ?: cause.javaClass.simpleName)
        }
        if (binderResult == "ok") return binderResult
        return try {
            val (success, output) = runSystemCommand(command)
            if (success && !output.contains("Error:", ignoreCase = true) &&
                !output.contains("Exception", ignoreCase = true)) "ok"
            else "System installer launch failed: " + output.take(250) + "; Binder: " + binderResult
        } catch (failure: Exception) {
            "System installer unavailable: " + (failure.message ?: failure.javaClass.simpleName) +
                "; Binder: " + binderResult
        }
    }

    private fun runSystemCommand(args: List<String>): Pair<Boolean, String> {
        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        if (!process.waitFor(12, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return false to "Command timed out"
        }
        return (process.exitValue() == 0) to process.inputStream.bufferedReader().use {
            it.readText().take(4096)
        }
    }

    companion object {
        private const val USER_CURRENT = -2
        const val DESCRIPTOR = "dev.n2k02.localadb.LocalHelperService"
        const val PING = IBinder.FIRST_CALL_TRANSACTION
        const val INSTALL_ANDROID_AUTO = IBinder.FIRST_CALL_TRANSACTION + 1
        const val DESTROY = 16777115
    }
}

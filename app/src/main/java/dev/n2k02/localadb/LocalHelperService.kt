package dev.n2k02.localadb

import android.content.Intent
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
     * Only the two known Android installer components and our fixed APK URI
     * are accepted; this Binder cannot run arbitrary shell input.
     */
    private fun startSystemInstaller(intent: Intent, resultTo: IBinder?): String {
        check(Process.myUid() == Process.SHELL_UID) { "Installer helper is not UID 2000" }
        check(intent.action == Intent.ACTION_INSTALL_PACKAGE) { "Invalid install action" }
        val component = intent.component?.flattenToString().orEmpty()
        val uri = intent.dataString.orEmpty()
        val expected = "content://dev.n2k02.localadb.android-auto-apk/selected.apk"
        val generic = "content://dev.n2k02.localadb.apk-install/selected.apk"
        if (uri != generic) check(uri == expected) { "Unexpected installation URI" }
        val command = if (uri == expected) AndroidAutoInstallPolicy.shellCommand(
            "dev.n2k02.localadb", component
        ) else { 
            check(component == AndroidAutoInstallPolicy.GOOGLE_INSTALLER ||
                component == AndroidAutoInstallPolicy.AOSP_INSTALLER) { "Unsupported installer" }
            "am start -n " + component +
                " -a android.intent.action.INSTALL_PACKAGE" +
                " -d " + generic + " -t application/vnd.android.package-archive"
        }
        return if (Build.VERSION.SDK_INT <= 34) {
            // Android 14 and earlier validate the result caller. Supplying the
            // LocalAdb activity token keeps LocalAdb as the permission requester.
            checkNotNull(resultTo) { "Missing requesting activity token" }
            try {
                val service = if (Build.VERSION.SDK_INT >= 29)
                    Class.forName("android.app.ActivityTaskManager")
                        .getMethod("getService").invoke(null)
                else ActivityManager::class.java.getMethod("getService").invoke(null)
                val method = service.javaClass.methods.firstOrNull { m ->
                    m.name == "startActivity" &&
                        ((m.parameterTypes.size == 11 && m.parameterTypes[3] == Intent::class.java) ||
                            (m.parameterTypes.size == 10 && m.parameterTypes[2] == Intent::class.java))
                } ?: error("No supported system startActivity signature")
                val args = if (method.parameterTypes.size == 11)
                    arrayOf(null, "com.android.shell", null, intent, intent.type,
                        resultTo, null, 1720, 0, null, null)
                else arrayOf(null, "com.android.shell", intent, intent.type,
                    resultTo, null, 1720, 0, null, null)
                val result = method.invoke(service, *args) as Int
                if (result >= 0) "ok" else "System installer start failed: " + result
            } catch (failure: InvocationTargetException) {
                "System installer error: " + failure.targetException.message
            }
        } else {
            val process = ProcessBuilder("/system/bin/sh", "-c", command)
                .redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText().take(2048) }
            if (process.waitFor() == 0 && !output.contains("Error:", ignoreCase = true))
                "ok"
            else "Installer failed: " + output
        }
    }

    companion object {
        const val DESCRIPTOR = "dev.n2k02.localadb.LocalHelperService"
        const val PING = IBinder.FIRST_CALL_TRANSACTION
        const val INSTALL_ANDROID_AUTO = IBinder.FIRST_CALL_TRANSACTION + 1
        const val DESTROY = 16777115
    }
}

package dev.n2k02.localadb

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import java.io.FileInputStream
import java.util.concurrent.TimeUnit

/**
 * Dedicated Shizuku UserService for direct APK installation.
 * A separate service identity avoids older LocalHelperService Binder protocols.
 * Only a read-only APK descriptor is accepted, never shell command text or a path.
 */
class DirectApkInstallService : Binder() {
    init { attachInterface(null, DESCRIPTOR) }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == DESTROY) kotlin.system.exitProcess(0)
        if (code == COMMAND) {
            data.enforceInterface(DESCRIPTOR)
            check(Process.myUid() == Process.SHELL_UID) { "Direct installer requires shell UID" }
            val operation = data.readString()
            when (operation) {
                OP_PING -> {
                    val challenge = data.readString()
                    checkNotNull(reply).apply {
                        writeNoException()
                        writeInt(Process.myUid())
                        writeString(challenge)
                        writeInt(PROTOCOL)
                    }
                }
                OP_INSTALL -> {
                    val declared = data.readLong()
                    val apk = ParcelFileDescriptor.CREATOR.createFromParcel(data)
                    val result = try {
                        install(apk, declared)
                    } finally {
                        apk.close()
                    }
                    checkNotNull(reply).apply {
                        writeNoException()
                        writeString(result)
                    }
                }
                else -> error("Unsupported APK installer operation")
            }
            return true
        }
        return super.onTransact(code, data, reply, flags)
    }

    private fun install(apk: ParcelFileDescriptor, bytes: Long): String {
        require(bytes in 1..ApkInstallPolicy.MAX_APK_BYTES) { "APK size is invalid" }
        check(Os.fstat(apk.fileDescriptor).st_size == bytes) { "APK size changed" }
        val process = ProcessBuilder(
            "/system/bin/pm", "install", "-r", "-t", "-S", bytes.toString(), "-"
        ).redirectErrorStream(true).start()
        try {
            var copied = 0L
            val input = FileInputStream(apk.fileDescriptor)
            process.outputStream.use { output ->
                val buffer = ByteArray(64 * 1024)
                while (copied < bytes) {
                    val count = input.read(buffer, 0,
                        minOf(buffer.size.toLong(), bytes - copied).toInt())
                    check(count > 0) { "APK data ended unexpectedly" }
                    output.write(buffer, 0, count)
                    copied += count
                }
            }
            if (!process.waitFor(180, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                error("Android package manager timed out")
            }
            val output = process.inputStream.bufferedReader().use { it.readText().take(4096) }
            check(process.exitValue() == 0 && output.lineSequence().any { it.trim() == "Success" }) {
                output.ifBlank { "Package manager could not install APK" }
            }
            return "Success"
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    companion object {
        const val DESCRIPTOR = "dev.n2k02.localadb.DirectApkInstallService"
        const val PROTOCOL = 2
        const val COMMAND = IBinder.FIRST_CALL_TRANSACTION
        const val OP_PING = "ping"
        const val OP_INSTALL = "install"
        const val DESTROY = 16777115
    }
}

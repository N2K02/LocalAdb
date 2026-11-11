package dev.n2k02.localadb

import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.util.Log
import java.io.FileInputStream
import java.util.concurrent.TimeUnit

/**
 * Shizuku UserService following the upstream AIDL Stub pattern.
 * Only APK FD + length may be supplied; all shell arguments are fixed.
 * Runs as shell UID, with pm installation sessions for structured results.
 */
class DirectApkInstallService : IDirectApkInstallService.Stub() {
    override fun destroy() {
        // Shizuku's reserved lifecycle method.
        kotlin.system.exitProcess(0)
    }

    override fun getServiceUid(): Int = Process.myUid()

    override fun installApk(
        apk: ParcelFileDescriptor?, length: Long, installerPackageName: String?
    ): String {
        check(Process.myUid() == Process.SHELL_UID) { "Direct install requires ADB shell UID" }
        requireNotNull(apk) { "APK descriptor missing" }
        require(length in 1..ApkInstallPolicy.MAX_APK_BYTES) { "Invalid APK size" }
        check(Os.fstat(apk.fileDescriptor).st_size == length) { "APK size changed" }

        // Never accept arbitrary package names or shell arguments from Binder callers.
        val createArgs = DirectInstallAttribution.sessionCreateArgs(
            installerPackageName?.ifBlank { null }
        )
        var session: String? = null
        try {
            val created = runCommand(createArgs)
            val id = Regex("""\[(\d+)\]""").find(created)?.groupValues?.get(1)
                ?: error("Cannot determine package install session: ${created.take(200)}")
            session = id
            Log.i(TAG, "Created install session")

            val process = ProcessBuilder(
                "/system/bin/pm", "install-write", "-S", length.toString(),
                id, "base.apk", "-"
            ).redirectErrorStream(true).start()
            try {
                var written = 0L
                val input = FileInputStream(apk.fileDescriptor)
                process.outputStream.use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (written < length) {
                        val count = input.read(buffer, 0,
                            minOf(buffer.size.toLong(), length - written).toInt())
                        check(count > 0) { "APK stream ended early" }
                        output.write(buffer, 0, count)
                        written += count
                    }
                    output.flush()
                }
                val output = finishCommand(process)
                check(output.contains("Success", ignoreCase = true)) {
                    "APK transfer failed: ${output.take(300)}"
                }
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
            Log.i(TAG, "APK transfer completed")
            val outcome = runCommand(listOf("install-commit", id))
            check(outcome.contains("Success", ignoreCase = true)) {
                "Package manager: ${outcome.take(300)}"
            }
            Log.i(TAG, "Install session committed")
            session = null
            return "Success"
        } finally {
            session?.let { id ->
                runCatching { runCommand(listOf("install-abandon", id)) }
                Log.w(TAG, "Install session abandoned after failure")
            }
            apk.close()
        }
    }

    private fun runCommand(args: List<String>): String {
        val process = ProcessBuilder(listOf("/system/bin/pm") + args)
            .redirectErrorStream(true).start()
        return try { finishCommand(process) } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun finishCommand(process: java.lang.Process): String {
        check(process.waitFor(180, TimeUnit.SECONDS)) { "Package manager timed out" }
        val output = process.inputStream.bufferedReader().use { it.readText().take(4096) }
        check(process.exitValue() == 0) {
            "Package manager returned ${process.exitValue()}: ${output.take(400)}"
        }
        return output.trim()
    }

    companion object {
        private const val TAG = "LocalAdbDirectInstall"
    }
}

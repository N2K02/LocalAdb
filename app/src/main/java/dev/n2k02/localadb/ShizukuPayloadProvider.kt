package dev.n2k02.localadb

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * The embedded server is exposed to the VERIFIED shell UID only.
 *
 * Do not stream an ~35 MB APK through a ParcelFileDescriptor pipe: if the
 * content(1) reader stalls or closes its end, the pipe writer can block while
 * the caller's ADB shell is still waiting for EOF. Instead materialize a
 * checksum-verified private file and return a read-only seekable FD. Android
 * transfers the FD to the shell; the shell cannot browse the private cache.
 */
class ShizukuPayloadProvider : ContentProvider() {
    private companion object {
        const val TAG = "LocalAdbShizuku"
        const val EMBEDDED_ASSET = "embedded-shizuku-server.apk"
        const val EMBEDDED_SHA256 =
            "f75b8b74727d281f3d7fd242298f676bfb4ee81d2feb5298aa719b05284fa149"
        const val MAX_STARTER_BYTES = 16L * 1024 * 1024
        private val prepareLock = Any()
        private var preparedForProcess = false
        private var preparedStarter = false

        fun sha256(data: ByteArray): String =
            data.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        // The provider's manifest readPermission is defense in depth; enforce
        // the real Binder caller UID even if Android permission rules change.
        if (Binder.getCallingUid() != Process.SHELL_UID) {
            throw SecurityException("Only ADB shell UID 2000 may read Shizuku payload")
        }
        val which = uri.pathSegments.singleOrNull()
        if (mode != "r" || (which != "embedded" && which != "starter")) {
            throw FileNotFoundException("Unsupported embedded payload request")
        }
        val app = context ?: throw FileNotFoundException("LocalAdb is unavailable")
        val target = File(app.cacheDir,
            if (which == "embedded") "localadb-embedded-shizuku.apk"
            else "localadb-embedded-shizuku-starter.so")
        val started = SystemClock.elapsedRealtime()
        try {
            synchronized(prepareLock) {
                when (which) {
                    "embedded" -> if (!preparedForProcess || !target.isFile) {
                        writeBundledApk(app, target)
                        preparedForProcess = true
                    }
                    "starter" -> if (!preparedStarter || !target.isFile) {
                        writeStarter(app, target)
                        preparedStarter = true
                    }
                }
            }
            Log.i(TAG, "Prepared $which payload FD (" + target.length() +
                " bytes, " + (SystemClock.elapsedRealtime() - started) + " ms)")
            return ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (error: Exception) {
            Log.e(TAG, "Could not prepare $which payload (" +
                (SystemClock.elapsedRealtime() - started) + " ms)", error)
            throw FileNotFoundException("LocalAdb could not prepare " + which +
                " payload: " + (error.message ?: error.javaClass.simpleName).take(160))
        }
    }

    private fun writeBundledApk(app: android.content.Context, target: File) {
        val temp = File.createTempFile("localadb-apk-", ".tmp", app.cacheDir)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            app.assets.open(EMBEDDED_ASSET).use { source ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = source.read(buffer)
                        if (n < 0) break
                        if (n == 0) continue
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                    }
                    output.fd.sync()
                }
            }
            val actual = sha256(digest.digest())
            check(actual == EMBEDDED_SHA256) { "Embedded server SHA-256 mismatch" }
            replaceAtomically(temp, target)
        } finally {
            temp.delete()
        }
    }

    private fun writeStarter(app: android.content.Context, target: File) {
        val preferred = "lib/" + Build.SUPPORTED_ABIS.firstOrNull().orEmpty() +
            "/libshizuku.so"
        val temp = File.createTempFile("localadb-starter-", ".tmp", app.cacheDir)
        try {
            var extracted = false
            app.assets.open(EMBEDDED_ASSET).use { apk ->
                ZipInputStream(apk).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.name != preferred) continue
                        FileOutputStream(temp).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                val n = zip.read(buffer)
                                if (n < 0) break
                                if (n == 0) continue
                                total += n
                                check(total <= MAX_STARTER_BYTES) {
                                    "Native starter exceeds expected size"
                                }
                                output.write(buffer, 0, n)
                            }
                            output.fd.sync()
                        }
                        extracted = true
                        break
                    }
                }
            }
            check(extracted && temp.length() >= 4) {
                "No native Shizuku starter for " + Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
            }
            temp.inputStream().use { file ->
                val elf = ByteArray(4)
                check(file.read(elf) == 4 && elf.contentEquals(
                    byteArrayOf(0x7f, 0x45, 0x4c, 0x46)
                )) { "Bundled starter is not an ELF executable" }
            }
            replaceAtomically(temp, target)
        } finally {
            temp.delete()
        }
    }

    private fun replaceAtomically(temp: File, destination: File) {
        check(temp.renameTo(destination)) { "Could not stage bundled server payload" }
    }

    override fun getType(uri: Uri): String? = "application/octet-stream"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<out String>?): Int = 0
}

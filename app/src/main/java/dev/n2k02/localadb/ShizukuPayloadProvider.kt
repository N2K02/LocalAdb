package dev.n2k02.localadb

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
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
 * Checksum-pinned Shizuku-Next server APK, native starter and Rish JNI library
 * are accessible through verified, seekable, read-only FDs to ADB UID 2000 only.
 *
 * The native starter may succeed while the server crashes if librish.so is not
 * installed under the native starter's -Dshizuku.library.path directory.
 */
class ShizukuPayloadProvider : ContentProvider() {
    private companion object {
        const val TAG = "LocalAdbShizuku"
        const val ASSET = "embedded-shizuku-server.apk"
        const val EXPECTED_SHA256 =
            "f75b8b74727d281f3d7fd242298f676bfb4ee81d2feb5298aa719b05284fa149"
        const val MAX_NATIVE_BYTES = 16L * 1024 * 1024
        val prepareLock = Any()
        var preparedApk = false
        val preparedNative = mutableSetOf<String>()
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (Binder.getCallingUid() != Process.SHELL_UID) {
            throw SecurityException("Only ADB shell UID 2000 can read Shizuku payloads")
        }
        val requested = uri.pathSegments.singleOrNull()
            ?: throw FileNotFoundException("Missing Shizuku payload name")
        if (mode != "r" || requested !in setOf("embedded", "starter", "rish")) {
            throw FileNotFoundException("Unsupported Shizuku payload request")
        }
        val app = context ?: throw FileNotFoundException("LocalAdb is unavailable")
        val started = SystemClock.elapsedRealtime()
        try {
            val apkFile = File(app.cacheDir, "localadb-embedded-shizuku.apk")
            val target = if (requested == "embedded") apkFile
                else File(app.cacheDir, "localadb-embedded-shizuku-" + requested + ".so")
            synchronized(prepareLock) {
                // All JNI files come from the *same* checksum-verified APK.
                if (!preparedApk || !apkFile.isFile) {
                    writeVerifiedApk(app, apkFile)
                    preparedApk = true
                }
                if (requested != "embedded" &&
                    (requested !in preparedNative || !target.isFile)) {
                    val libraries = ShizukuNativeLibraries.select(Build.SUPPORTED_ABIS.toList())
                    val entryName = when (requested) {
                        "starter" -> libraries.starterEntry
                        "rish" -> libraries.rishEntry
                        else -> error("Unsupported native payload")
                    }
                    extractNative(apkFile, entryName, target)
                    preparedNative.add(requested)
                }
            }
            Log.i(TAG, "Prepared Shizuku " + requested + ": " + target.length() +
                " bytes in " + (SystemClock.elapsedRealtime() - started) + " ms")
            return ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (error: Exception) {
            Log.e(TAG, "Failed to prepare Shizuku " + requested + " payload", error)
            throw FileNotFoundException(
                "Could not prepare " + requested + ": " +
                    (error.message ?: error.javaClass.simpleName).take(200))
        }
    }

    private fun writeVerifiedApk(context: Context, target: File) {
        val temp = File.createTempFile("localadb-apk-", ".tmp", context.cacheDir)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            context.assets.open(ASSET).use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            check(actual == EXPECTED_SHA256) { "Embedded Shizuku APK SHA-256 mismatch" }
            replaceAtomically(temp, target)
        } finally {
            temp.delete()
        }
    }

    private fun extractNative(apk: File, entryName: String, target: File) {
        val temp = File.createTempFile("localadb-jni-", ".tmp", target.parentFile)
        try {
            var found = false
            ZipInputStream(apk.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name != entryName) continue
                    FileOutputStream(temp).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            total += count
                            check(total <= MAX_NATIVE_BYTES) {
                                "JNI library too large: " + entryName
                            }
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                    found = true
                    break
                }
            }
            check(found && temp.length() >= 4) {
                "Required native library missing from pinned APK: " + entryName
            }
            temp.inputStream().use { input ->
                val elf = ByteArray(4)
                check(input.read(elf) == 4 &&
                    elf.contentEquals(byteArrayOf(0x7f, 0x45, 0x4c, 0x46))) {
                    "Bundled native library has invalid ELF header: " + entryName
                }
            }
            replaceAtomically(temp, target)
        } finally {
            temp.delete()
        }
    }

    private fun replaceAtomically(temp: File, target: File) {
        check(temp.renameTo(target)) { "Could not stage Shizuku payload" }
    }

    override fun getType(uri: Uri): String = "application/octet-stream"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<out String>?): Int = 0
}

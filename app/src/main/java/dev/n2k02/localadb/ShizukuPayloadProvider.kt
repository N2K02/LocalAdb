package dev.n2k02.localadb

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.util.Log
import java.util.zip.ZipInputStream
import android.os.ParcelFileDescriptor
import android.os.Process
import java.io.FileNotFoundException

/**
 * Delivers the bundled Apache-2.0 server only to the verified Android shell.
 * The third-party APK is NOT installed as an external Shizuku application.
 */
class ShizukuPayloadProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (Binder.getCallingUid() != Process.SHELL_UID) {
            throw SecurityException("Only the Android shell UID 2000 can read the server payload")
        }
        if (mode != "r" || uri.pathSegments.size != 1 ||
            uri.lastPathSegment !in listOf("embedded", "starter")) {
            throw FileNotFoundException("No such bundled payload")
        }
        val application = context ?: throw FileNotFoundException("Application not initialized")
        val starter = uri.lastPathSegment == "starter"
        val (read, write) = ParcelFileDescriptor.createPipe()
        Thread({
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(write).use { output ->
                    application.assets.open("embedded-shizuku-server.apk").use { input ->
                        if (starter) {
                            // Shizuku-Next's *own* native starter has the detach,
                            // SELinux and hidden-API setup needed for app_process.
                            // No native executable is downloaded at runtime.
                            ZipInputStream(input).use { zip ->
                                // Prefer the primary device ABI. Selecting the first
                                // ZIP match could accidentally choose 32-bit on arm64.
                                val preferred = "lib/" +
                                    Build.SUPPORTED_ABIS.firstOrNull().orEmpty() +
                                    "/libshizuku.so"
                                var entry = zip.nextEntry
                                var found = false
                                while (entry != null) {
                                    if (entry.name == preferred) {
                                        zip.copyTo(output, bufferSize = 64 * 1024)
                                        found = true
                                        break
                                    }
                                    entry = zip.nextEntry
                                }
                                if (!found) {
                                    Log.e("LocalAdbShizuku",
                                        "No supported native starter in bundled APK")
                                }
                            }
                        } else {
                            input.copyTo(output, bufferSize = 64 * 1024)
                        }
                    }
                }
            } catch (failure: Exception) {
                Log.e("LocalAdbShizuku",
                    "Could not stream embedded Shizuku payload", failure)
                runCatching { write.close() }
            }
        }, "localadb-shizuku-asset").apply { isDaemon = true; start() }
        return read
    }

    override fun getType(uri: Uri): String? = null
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<out String>?): Int = 0
}

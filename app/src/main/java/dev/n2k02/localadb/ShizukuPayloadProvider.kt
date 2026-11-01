package dev.n2k02.localadb

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
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
        if (mode != "r" || uri.lastPathSegment != "embedded" ||
            uri.pathSegments.size != 1) {
            throw FileNotFoundException("No such bundled payload")
        }
        val application = context ?: throw FileNotFoundException("Application not initialized")
        val (read, write) = ParcelFileDescriptor.createPipe()
        Thread({
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(write).use { output ->
                    application.assets.open("embedded-shizuku-server.apk").use { input ->
                        input.copyTo(output, bufferSize = 64 * 1024)
                    }
                }
            } catch (_: Exception) {
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

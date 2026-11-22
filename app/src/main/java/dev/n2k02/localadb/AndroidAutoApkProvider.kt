package dev.n2k02.localadb

import android.content.ContentProvider
import android.content.Context
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Serves only the explicitly selected, read-only APK to a system installer
 * holding an explicit URI grant. Never exposes arbitrary cache paths.
 */
class AndroidAutoApkProvider : ContentProvider() {
    companion object {
        internal const val SESSION_LIFETIME_MS = 10 * 60_000L
        private fun directory(context: Context) = File(context.cacheDir, "android_auto_installer")
        fun file(context: Context): File = File(directory(context), "selected.apk")

        internal fun sessionFile(context: Context, session: String): File {
            require(ApkInstallPolicy.validSession(session))
            return File(directory(context), "$session.apk")
        }

        internal fun uri(context: Context, session: String): Uri =
            Uri.parse("content://${context.packageName}.android-auto-apk/${sessionFile(context, session).name}")

        internal fun cleanupExpired(context: Context, now: Long = System.currentTimeMillis()) {
            directory(context).listFiles()?.forEach { file ->
                val id = file.name.removeSuffix(".apk")
                if (id != file.name && ApkInstallPolicy.validSession(id) &&
                    now - file.lastModified() >= SESSION_LIFETIME_MS) file.delete()
            }
        }
    }

    private fun selected(uri: Uri): File {
        val app = requireNotNull(context)
        val session = AndroidAutoInstallPolicy.sessionFromUri(uri.toString(),
            "${app.packageName}.android-auto-apk") ?: throw FileNotFoundException("Invalid APK session")
        val source = sessionFile(app, session)
        if (!source.isFile) throw FileNotFoundException("No APK staged for this session")
        return source
    }

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read-only provider")
        return ParcelFileDescriptor.open(selected(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val source = selected(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            val row = arrayOfNulls<Any>(columns.size)
            columns.forEachIndexed { index, column ->
                row[index] = when (column) {
                    OpenableColumns.DISPLAY_NAME -> source.name
                    OpenableColumns.SIZE -> source.length()
                    else -> null
                }
            }
            addRow(row)
        }
    }

    override fun getType(uri: Uri) = selected(uri).let { "application/vnd.android.package-archive" }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}

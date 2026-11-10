package dev.n2k02.localadb

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

/** Isolated immutable APK snapshots. Each URI addresses one install session. */
class ApkInstallProvider : ContentProvider() {
    companion object {
        private val TOKEN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        fun validSession(id: String): Boolean = TOKEN.matches(id) &&
            runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
        fun file(context: Context, session: String): File {
            require(validSession(session)) { "Invalid APK session" }
            return File(File(context.cacheDir, "apk_install"), "$session.apk")
        }
        fun uri(context: Context, session: String): Uri {
            require(validSession(session))
            return Uri.Builder().scheme("content")
                .authority("${context.packageName}.apk-install")
                .appendPath("$session.apk").build()
        }
        fun cleanup(context: Context, session: String) {
            if (validSession(session)) file(context, session).delete()
        }
    }

    private fun selected(uri: Uri): File {
        val context = requireNotNull(context)
        if (uri.authority != "${context.packageName}.apk-install" ||
            uri.pathSegments.size != 1) throw FileNotFoundException("Invalid APK URI")
        val part = uri.pathSegments.single()
        if (!part.endsWith(".apk") || !validSession(part.removeSuffix(".apk")))
            throw FileNotFoundException("Unknown APK session")
        val source = file(context, part.removeSuffix(".apk"))
        if (!source.isFile) throw FileNotFoundException("APK is no longer available")
        return source
    }

    override fun onCreate() = true
    override fun getType(uri: Uri): String =
        selected(uri).let { "application/vnd.android.package-archive" }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read-only APK")
        return ParcelFileDescriptor.open(selected(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val file = selected(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map<String, Any?> { column -> when (column) {
                OpenableColumns.DISPLAY_NAME -> file.name
                OpenableColumns.SIZE -> file.length()
                else -> null
            } }.toTypedArray())
        }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}

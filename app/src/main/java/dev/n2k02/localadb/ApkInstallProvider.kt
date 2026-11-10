package dev.n2k02.localadb

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/** Single staged APK exposed only with a temporary read URI grant. */
class ApkInstallProvider : ContentProvider() {
    companion object {
        fun file(context: android.content.Context) =
            File(File(context.cacheDir, "apk_install"), "selected.apk")
        fun uri(context: android.content.Context): Uri =
            Uri.parse("content://${context.packageName}.apk-install/selected.apk")
    }

    private fun selected(uri: Uri): File {
        if (uri.path != "/selected.apk") throw FileNotFoundException("Unsupported path")
        return file(requireNotNull(context)).also {
            if (!it.isFile) throw FileNotFoundException("No APK staged")
        }
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
        val source = selected(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map { name -> when (name) {
                OpenableColumns.DISPLAY_NAME -> source.name
                OpenableColumns.SIZE -> source.length()
                else -> null
            } }.toTypedArray<Any?>())
        }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}

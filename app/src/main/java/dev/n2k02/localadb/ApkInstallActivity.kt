package dev.n2k02.localadb

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * Handles explicit user selections from a file manager. All untrusted inputs
 * are staged privately and passed to Android's package installer with a
 * read-only grant; no file:// exposure or privileged install shortcuts.
 */
class ApkInstallActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var staged = false
    private var awaitingPermission = false
    private val tag = "LocalAdbApkInstall"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            }
            else -> null
        }
        if (uri == null || uri.scheme != "content") {
            fail("Select an APK from a file manager.")
            return
        }
        worker.execute {
            try {
                val destination = ApkInstallProvider.file(this)
                require(destination.parentFile?.mkdirs() == true ||
                    destination.parentFile?.isDirectory == true)
                val temporary = File(destination.parentFile, "incoming.partial")
                try {
                    var bytes = 0L
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(temporary).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                bytes += count
                                require(bytes <= 512L * 1024 * 1024) { "APK is too large" }
                                output.write(buffer, 0, count)
                            }
                        }
                    } ?: error("Cannot read APK")
                    require(bytes > 0) { "APK is empty" }
                    if (destination.exists()) require(destination.delete())
                    require(temporary.renameTo(destination)) { "Unable to stage APK" }
                    val info = packageManager.getPackageArchiveInfo(
                        destination.absolutePath, PackageManager.GET_ACTIVITIES
                    ) ?: error("Not a valid single APK")
                    val name = info.applicationInfo?.let {
                        it.sourceDir = destination.absolutePath
                        it.publicSourceDir = destination.absolutePath
                        packageManager.getApplicationLabel(it).toString()
                    } ?: info.packageName
                    val message = "${info.packageName}\nVersion: ${info.versionName ?: "unknown"}"
                    main.post {
                        if (isFinishing || isDestroyed) return@post
                        staged = true
                        AlertDialog.Builder(this)
                            .setTitle("Install $name?")
                            .setMessage(message)
                            .setNegativeButton("Cancel") { _, _ -> finish() }
                            .setPositiveButton("Install") { _, _ -> openInstaller() }
                            .setOnCancelListener { finish() }
                            .show()
                    }
                } finally {
                    temporary.delete()
                }
            } catch (failure: Exception) {
                Log.w(tag, "APK validation/staging failed", failure)
                main.post { fail(failure.message ?: "Unable to read APK") }
            }
        }
    }

    private fun openInstaller() {
        if (!staged) return
        if (!packageManager.canRequestPackageInstalls()) {
            awaitingPermission = true
            AlertDialog.Builder(this)
                .setTitle("Allow APK installation")
                .setMessage("Android requires permission for LocalAdb to install apps from this source.")
                .setNegativeButton("Cancel") { _, _ -> finish() }
                .setPositiveButton("Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")))
                }
                .show()
            return
        }
        try {
            val uri = ApkInstallProvider.uri(this)
            val install = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                clipData = ClipData.newUri(contentResolver, "APK", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            Log.i(tag, "Launching Android package installer")
            startActivity(install)
            finish()
        } catch (failure: Exception) {
            Log.e(tag, "Installer launch failed", failure)
            fail("Could not open Android installer: ${failure.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        if (awaitingPermission && packageManager.canRequestPackageInstalls()) {
            awaitingPermission = false
            openInstaller()
        }
    }

    private fun fail(message: String) {
        if (!isFinishing) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}

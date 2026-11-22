package dev.n2k02.localadb

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Read-only comparison: package metadata is not Android Auto authorization. */
internal object AndroidAutoInstallDiagnostics {
    private val packages = listOf(
        "com.fcaronte.aabrowser" to "AABrowser",
        "com.aayoutube.app" to "AAYouTube"
    )

    fun summary(context: Context): String = buildString {
        appendLine("Android Auto installation comparison")
        appendLine("Installation-source metadata does not guarantee launcher visibility or Android Auto approval.")
        val pm = context.packageManager
        for ((pkg, label) in packages) {
            appendLine()
            appendLine("$label ($pkg)")
            val info = runCatching {
                if (Build.VERSION.SDK_INT >= 33)
                    pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(
                        (PackageManager.GET_SERVICES or PackageManager.GET_META_DATA).toLong()))
                else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(pkg, PackageManager.GET_SERVICES or PackageManager.GET_META_DATA)
                }
            }.getOrNull()
            if (info == null) {
                appendLine("Not installed or package metadata unavailable")
                continue
            }
            appendLine("Version: ${info.versionName ?: "unknown"}")
            appendLine("Enabled projection services:")
            val projected = info.services.orEmpty().filter {
                it.name.contains("CarService", true) || it.name.contains("Projection", true)
            }
            if (projected.isEmpty()) appendLine("  Cannot identify from service names")
            else projected.forEach { appendLine("  ${it.name} (enabled=${it.enabled}, exported=${it.exported})") }
            if (Build.VERSION.SDK_INT >= 30) {
                val src = runCatching { pm.getInstallSourceInfo(pkg) }.getOrNull()
                appendLine("Initiator: ${src?.initiatingPackageName ?: "unknown"}")
                appendLine("Installer: ${src?.installingPackageName ?: "unknown"}")
                appendLine("Observed installer policy: " + when {
                    src == null -> "unknown"
                    AndroidAutoInstallPolicy.accepts(src.initiatingPackageName,
                        src.installingPackageName, Build.VERSION.SDK_INT) -> "metadata matches known checks"
                    else -> "metadata differs from known checks"
                })
            } else {
                @Suppress("DEPRECATION")
                val installer = runCatching { pm.getInstallerPackageName(pkg) }.getOrNull()
                appendLine("Installer: ${installer ?: "unknown"}")
            }
        }
    }
}

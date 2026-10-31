package dev.n2k02.localadb

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Shizuku permission grants are manager-only; Android developer grants are separate. */
internal data class AppAccess(
    val packageName: String,
    val label: String,
    val uid: Int,
    val system: Boolean,
    val enabled: Boolean,
    val shizukuClient: Boolean,
    val declaredAdbPermissions: List<String>,
    val grantedAdbPermissions: Set<String>,
)

internal object AppAccessPolicy {
    const val SHIZUKU_PERMISSION = "moe.shizuku.manager.permission.API_V23"
    val supportedAdbPermissions = setOf(
        "android.permission.WRITE_SECURE_SETTINGS",
        "android.permission.DUMP",
        "android.permission.READ_LOGS",
        "android.permission.BATTERY_STATS",
        "android.permission.READ_DEVICE_CONFIG"
    )
    private val packagePattern = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
    fun validTarget(packageName: String, permission: String): Boolean =
        packagePattern.matches(packageName) && permission in supportedAdbPermissions
    fun onlyRequested(requested: Collection<String>): List<String> =
        requested.distinct().filter { it in supportedAdbPermissions }.sorted()
    fun isShizukuClient(requested: Collection<String>, supportsV3: Boolean) =
        SHIZUKU_PERMISSION in requested && supportsV3
    fun filter(apps: List<AppAccess>, search: String, kind: String,
               shizukuOnly: Boolean, grantedOnly: Boolean?): List<AppAccess> =
        apps.filter { app ->
            (search.isBlank() || app.label.contains(search.trim(), true) ||
                app.packageName.contains(search.trim(), true)) &&
            when (kind) {
                "User" -> !app.system
                "System" -> app.system
                "Disabled" -> !app.enabled
                else -> true
            } &&
            (!shizukuOnly || app.shizukuClient) &&
            when (grantedOnly) {
                true -> app.grantedAdbPermissions.isNotEmpty()
                false -> app.declaredAdbPermissions.any { it !in app.grantedAdbPermissions }
                null -> true
            }
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
}

internal object AppAccessRepository {
    suspend fun list(context: Context): List<AppAccess> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_META_DATA
        @Suppress("DEPRECATION")
        val installed = pm.getInstalledPackages(flags)
        installed.asSequence()
            .filter { it.packageName != context.packageName }
            .mapNotNull { pkg ->
                val ai = pkg.applicationInfo ?: return@mapNotNull null
                val requested = pkg.requestedPermissions?.toList().orEmpty()
                val options = AppAccessPolicy.onlyRequested(requested)
                val shizuku = AppAccessPolicy.isShizukuClient(
                    requested, ai.metaData?.getBoolean("moe.shizuku.client.V3_SUPPORT") == true
                )
                if (!shizuku && options.isEmpty()) return@mapNotNull null
                AppAccess(
                    packageName = pkg.packageName,
                    label = runCatching { ai.loadLabel(pm).toString() }.getOrDefault(pkg.packageName),
                    uid = ai.uid,
                    system = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                    enabled = ai.enabled,
                    shizukuClient = shizuku,
                    declaredAdbPermissions = options,
                    grantedAdbPermissions = options.filterTo(mutableSetOf()) {
                        pm.checkPermission(it, pkg.packageName) == PackageManager.PERMISSION_GRANTED
                    }
                )
            }
            .distinctBy { it.packageName to it.uid }
            .toList()
    }

    private val mutex = Mutex()
    /** User-triggered, one declared permission, live shell check plus Android readback. */
    suspend fun changeAndroidGrant(
        context: Context, packageName: String, permission: String, grant: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                require(AppAccessPolicy.validTarget(packageName, permission)) {
                    "Unsupported Android permission or package"
                }
                val pm = context.packageManager
                @Suppress("DEPRECATION")
                val pkg = pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
                require(pkg.requestedPermissions?.contains(permission) == true) {
                    "App does not declare this permission"
                }
                check(AdbEngine.status.value.connected && AdbTransport.isConnected &&
                    AdbTransport.exec("id -u", timeoutMs = 3_000).getOrNull()?.trim() == "2000") {
                    "Authorized ADB shell UID 2000 required"
                }
                val command = "pm " + (if (grant) "grant" else "revoke") +
                    " " + packageName + " " + permission
                val output = AdbTransport.exec(command, timeoutMs = 10_000).getOrThrow()
                val expected = if (grant) PackageManager.PERMISSION_GRANTED
                               else PackageManager.PERMISSION_DENIED
                var confirmed = false
                for (i in 0..3) {
                    if (pm.checkPermission(permission, packageName) == expected) {
                        confirmed = true
                        break
                    }
                    delay(180L)
                }
                check(confirmed) { "Android did not apply permission: " + output.take(140) }
            }
        }
    }

    private val managers = listOf("moe.shizuku.privileged.api", "moe.shizuku.manager")
    fun installedManager(context: Context): String? {
        val pm = context.packageManager
        return managers.firstOrNull { runCatching { pm.getPackageInfo(it, 0) }.isSuccess }
    }
    /** Shizuku's server verifies manager UID; delegate its grants to the real manager. */
    fun openShizukuManager(context: Context): Boolean {
        val pm = context.packageManager
        val name = installedManager(context) ?: return false
        val intent = pm.getLaunchIntentForPackage(name) ?: return false
        return runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }
    fun openProject(context: Context) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/rushiranpise/Shizuku-Next"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

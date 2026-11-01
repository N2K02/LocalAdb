package dev.n2k02.localadb

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.net.Uri
import android.os.Process
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A Shizuku permission is controlled by the registered Shizuku manager's server binder.
 * Android developer permissions can be changed by our own live, authorized ADB shell.
 * Never represent one as the other.
 */
internal data class AppAccess(
    val packageName: String,
    val label: String,
    val uid: Int,
    val system: Boolean,
    val enabled: Boolean,
    val shizukuClient: Boolean,
    val declaredAdbPermissions: List<String>,
    val grantedAdbPermissions: Set<String>,
    val shizukuV3: Boolean = false,
    val grantableAdbPermissions: Set<String> = emptySet(),
    val hasLauncher: Boolean = true,
)

internal object AppAccessPolicy {
    const val SHIZUKU_PERMISSION = "moe.shizuku.manager.permission.API_V23"
    const val SHIZUKU_V3_META = "moe.shizuku.client.V3_SUPPORT"
    private val packagePattern = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
    // Only developer permissions in this explicit allowlist are even candidates.
    val supportedAdbPermissions = setOf(
        "android.permission.WRITE_SECURE_SETTINGS",
        "android.permission.DUMP",
        "android.permission.READ_LOGS",
        "android.permission.BATTERY_STATS",
        "android.permission.READ_DEVICE_CONFIG",
    )
    val shizukuManagers = listOf("moe.shizuku.privileged.api", "moe.shizuku.manager")

    fun validTarget(packageName: String, permission: String): Boolean =
        packagePattern.matches(packageName) && permission in supportedAdbPermissions

    fun onlyRequested(requested: Collection<String>): List<String> =
        requested.distinct().filter { it in supportedAdbPermissions }.sorted()

    // The declared API permission is the detection signal. V3 metadata distinguishes modern
    // Shizuku clients from older/legacy clients, not whether the user granted access.
    @Suppress("UNUSED_PARAMETER")
    fun isShizukuClient(requested: Collection<String>, supportsV3: Boolean): Boolean =
        SHIZUKU_PERMISSION in requested

    fun isDeveloperPermission(protectionLevel: Int): Boolean =
        protectionLevel and PermissionInfo.PROTECTION_FLAG_DEVELOPMENT != 0

    fun sameUser(appUid: Int, targetUid: Int): Boolean =
        appUid >= 0 && targetUid >= 0 && appUid / 100_000 == targetUid / 100_000

    fun preferredManager(installed: Collection<String>): String? =
        shizukuManagers.firstOrNull { it in installed }

    fun filter(
        apps: List<AppAccess>, search: String, kind: String,
        shizukuOnly: Boolean, grantedOnly: Boolean?,
    ): List<AppAccess> {
        val phrase = search.trim()
        return apps.filter { app ->
            (phrase.isEmpty() || app.label.contains(phrase, ignoreCase = true) ||
                app.packageName.contains(phrase, ignoreCase = true)) &&
                when (kind) {
                    "User" -> !app.system
                    "System" -> app.system
                    "Disabled" -> !app.enabled
                    "Hidden" -> !app.hasLauncher
                    else -> true
                } &&
                (!shizukuOnly || app.shizukuClient) &&
                when (grantedOnly) {
                    true -> app.grantedAdbPermissions.isNotEmpty()
                    false -> app.declaredAdbPermissions.any { it !in app.grantedAdbPermissions }
                    null -> true
                }
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label }
            .thenBy { it.packageName }.thenBy { it.uid })
    }
}

internal object AppAccessRepository {
    /**
     * Current Android user only. QUERY_ALL_PACKAGES makes eligible installed apps visible.
     * Shizuku authorization is intentionally UNKNOWN here: PackageManager does not expose
     * the manager's server-side authorization flags.
     *
     * Do not swallow PackageManager failures: the UI must distinguish a failed scan from
     * a genuinely empty list.
     */
    suspend fun list(context: Context): List<AppAccess> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_META_DATA
        @Suppress("DEPRECATION")
        val installed = pm.getInstalledPackages(flags)
        installed.asSequence()
            .filter { it.packageName != context.packageName }
            .mapNotNull { pkg ->
                val ai = pkg.applicationInfo ?: return@mapNotNull null
                if (!AppAccessPolicy.sameUser(Process.myUid(), ai.uid)) return@mapNotNull null
                val requested = pkg.requestedPermissions?.toList().orEmpty()
                val candidates = AppAccessPolicy.onlyRequested(requested)
                val shizuku = AppAccessPolicy.isShizukuClient(
                    requested, ai.metaData?.getBoolean(AppAccessPolicy.SHIZUKU_V3_META) == true
                )
                if (!shizuku && candidates.isEmpty()) return@mapNotNull null
                val allowed = candidates.filterTo(mutableSetOf()) { permission ->
                    isGrantable(pm, permission)
                }
                AppAccess(
                    packageName = pkg.packageName,
                    label = runCatching { ai.loadLabel(pm).toString() }.getOrDefault(pkg.packageName),
                    uid = ai.uid,
                    system = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                    enabled = ai.enabled,
                    shizukuClient = shizuku,
                    declaredAdbPermissions = candidates,
                    grantedAdbPermissions = candidates.filterTo(mutableSetOf()) {
                        pm.checkPermission(it, pkg.packageName) == PackageManager.PERMISSION_GRANTED
                    },
                    shizukuV3 = shizuku &&
                        ai.metaData?.getBoolean(AppAccessPolicy.SHIZUKU_V3_META) == true,
                    grantableAdbPermissions = allowed,
                    hasLauncher = pm.getLaunchIntentForPackage(pkg.packageName) != null,
                )
            }
            .distinctBy { it.packageName to it.uid }
            .toList()
    }

    @Suppress("DEPRECATION")
    private fun isGrantable(pm: PackageManager, permission: String): Boolean =
        runCatching {
            val info = pm.getPermissionInfo(permission, 0)
            AppAccessPolicy.isDeveloperPermission(info.protectionLevel)
        }.getOrDefault(false)

    private val mutationMutex = Mutex()

    /**
     * Explicit, individually confirmed user request ONLY. No bulk auto-grants.
     * The live shell UID is rechecked immediately before every command; Android's
     * PackageManager readback is the only success signal.
     */
    suspend fun changeAndroidGrant(
        context: Context, packageName: String, permission: String, grant: Boolean,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            try {
                Result.success(changeOne(context.applicationContext, packageName, permission, grant))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
        }
    }

    private suspend fun changeOne(
        context: Context, packageName: String, permission: String, grant: Boolean,
    ) {
        require(AppAccessPolicy.validTarget(packageName, permission)) {
            "Unsupported Android permission or package name"
        }
        require(packageName != context.packageName) {
            "LocalAdb's own permissions are managed automatically after UID 2000 verification"
        }
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val pkg = pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        val targetUid = pkg.applicationInfo?.uid ?: error("Application record unavailable")
        require(AppAccessPolicy.sameUser(Process.myUid(), targetUid)) {
            "Cross-profile permission changes are not supported"
        }
        require(pkg.requestedPermissions?.contains(permission) == true) {
            "The target app has not declared this Android permission"
        }
        check(isGrantable(pm, permission)) {
            "Android does not classify this permission as ADB-grantable"
        }
        check(AdbEngine.status.value.connected && AdbTransport.isConnected &&
            AdbTransport.exec("id -u", timeoutMs = 3_000).getOrNull()?.trim() == "2000") {
            "A live, authorized ADB shell UID 2000 is required"
        }
        val expected = if (grant) PackageManager.PERMISSION_GRANTED
                       else PackageManager.PERMISSION_DENIED
        val previous = pm.checkPermission(permission, packageName)
        if (previous == expected) return

        val userId = targetUid / 100_000
        val command = "pm " + (if (grant) "grant" else "revoke") +
            " --user " + userId + " " + packageName + " " + permission
        // ADB command failure is not proof of success; likewise shell output alone
        // is never proof of the resulting Android permission state.
        AdbTransport.exec(command, timeoutMs = 10_000).getOrThrow()
        repeat(5) {
            if (pm.checkPermission(permission, packageName) == expected) return
            delay(200)
        }
        error("Android did not confirm the permission change; the displayed state was not updated")
    }

    fun installedManager(context: Context): String? {
        val pm = context.packageManager
        val found = AppAccessPolicy.shizukuManagers.filter { pkg ->
            runCatching {
                val ai = pm.getApplicationInfo(pkg, 0)
                ai.enabled && pm.getLaunchIntentForPackage(pkg) != null
            }.getOrDefault(false)
        }
        return AppAccessPolicy.preferredManager(found)
    }

    /** The installed manager has the right UID; no undocumented exported component intents. */
    fun openShizukuManager(context: Context): Boolean {
        val name = installedManager(context) ?: return false
        val launch = context.packageManager.getLaunchIntentForPackage(name) ?: return false
        return runCatching {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }

    fun openProject(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/rushiranpise/Shizuku-Next"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrDefault(false)
}

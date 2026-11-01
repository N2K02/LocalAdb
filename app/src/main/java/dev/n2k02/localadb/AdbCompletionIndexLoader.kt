package dev.n2k02.localadb

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A read-only PackageManager snapshot for autocomplete; no ADB commands,
 * Shizuku privilege, network calls or permission mutations occur while typing.
 * QUERY_ALL_PACKAGES is already declared for LocalAdb's Apps page.
 */
internal object AdbCompletionIndexLoader {
    // Avoid rescanning every installed package on quick app switches/resumes.
    // A bounded TTL refreshes installation and permission suggestions automatically.
    private const val CACHE_FOR_MS = 60_000L
    @Volatile private var snapshot: CompletionIndex? = null
    @Volatile private var snapshotAtMs: Long = 0L

    suspend fun load(context: Context): CompletionIndex {
        val now = SystemClock.elapsedRealtime()
        snapshot?.let { cached ->
            if (now >= snapshotAtMs && now - snapshotAtMs < CACHE_FOR_MS) return cached
        }
        val loaded = withContext(Dispatchers.IO) {
        val pm = context.applicationContext.packageManager
        @Suppress("DEPRECATION")
        val installed = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        val names = LinkedHashSet<String>()
        val system = HashSet<String>()
        val requested = LinkedHashMap<String, List<String>>()
        val permissions = LinkedHashSet<String>()

        for (pkg in installed) {
            val name = pkg.packageName
            if (name.isBlank() || name.length > 200 || !names.add(name)) continue
            if (((pkg.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM) != 0) {
                system.add(name)
            }
            val declared = pkg.requestedPermissions.orEmpty()
                .asSequence().filter { it.isNotBlank() && it.length <= 220 }
                .distinct().sorted().take(300).toList()
            requested[name] = declared
            permissions.addAll(declared)
        }
        CompletionIndex(
            packages = names.sorted(),
            systemPackages = system,
            permissions = permissions.sorted(),
            declaredPermissions = requested,
        )
        }
        snapshot = loaded
        snapshotAtMs = SystemClock.elapsedRealtime()
        return loaded
    }
}

package dev.n2k02.localadb

/**
 * Shizuku-Next's native starter passes -Dshizuku.library.path=<dir>/lib/<short ABI>.
 * Android APKs use different JNI ABI names; match exactly or fail before launch.
 */
internal object ShizukuNativeLibraries {
    data class Target(val apkAbi: String, val serverAbi: String) {
        val starterEntry: String get() = "lib/" + apkAbi + "/libshizuku.so"
        val rishEntry: String get() = "lib/" + apkAbi + "/librish.so"
        fun shellRishPath(dir: String): String =
            dir + "/lib/" + serverAbi + "/librish.so"
    }

    private val mappings = mapOf(
        "arm64-v8a" to "arm64",
        "armeabi-v7a" to "arm",
        "x86_64" to "x86_64",
        "x86" to "x86",
    )

    fun select(supportedAbis: List<String>): Target {
        val apkAbi = supportedAbis.firstOrNull { it in mappings }
            ?: throw IllegalStateException("No supported Shizuku native ABI: " + supportedAbis)
        return Target(apkAbi, checkNotNull(mappings[apkAbi]))
    }
}

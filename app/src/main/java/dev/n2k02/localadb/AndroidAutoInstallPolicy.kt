package dev.n2k02.localadb

/** Small, testable compatibility policy. The Android Auto check is an observed
 * install-source gate, not proof that an APK actually implements Android Auto. */
internal object AndroidAutoInstallPolicy {
    const val VENDING = "com.android.vending"
    const val GOOGLE_INSTALLER = "com.google.android.packageinstaller/com.android.packageinstaller.InstallStart"
    const val AOSP_INSTALLER = "com.android.packageinstaller/com.android.packageinstaller.InstallStart"

    enum class Path { SHELL, APP }

    fun accepts(initiating: String?, installing: String?, sdk: Int): Boolean {
        if (installing != VENDING) return false
        if (sdk < 30) return true
        return initiating == VENDING || initiating == "com.google.android.packageinstaller" ||
            initiating == "com.android.packageinstaller"
    }

    fun firstPath(shellReady: Boolean, shellPreferred: Boolean): Path =
        if (shellReady && shellPreferred) Path.SHELL else Path.APP

    /** An old install that did not change cannot be called a successful attempt. */
    fun shouldRetry(changed: Boolean, accepted: Boolean, alreadyRetried: Boolean): Boolean =
        changed && !accepted && !alreadyRetried

    /** Only fixed, platform-owned installer components and a validated app authority. */
    fun shellCommand(packageName: String, component: String): String {
        require(packageName.matches(Regex("[A-Za-z0-9_.]+")))
        require(component == GOOGLE_INSTALLER || component == AOSP_INSTALLER)
        val uri = "content://" + packageName + ".android-auto-apk/selected.apk"
        return "am start -n " + component +
            " -a android.intent.action.INSTALL_PACKAGE" +
            " -d " + uri +
            " -t application/vnd.android.package-archive" +
            " --es android.intent.extra.INSTALLER_PACKAGE_NAME " + VENDING +
            " --ez android.intent.extra.NOT_UNKNOWN_SOURCE true" +
            " --ei android.intent.extra.INSTALL_REASON 1" +
            " --es android.intent.extra.REFERRER_NAME android-app://" + VENDING
    }
}

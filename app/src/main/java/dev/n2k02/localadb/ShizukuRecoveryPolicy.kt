package dev.n2k02.localadb

/** Pure lifecycle policy: a deliberate Stop always wins over automatic recovery. */
internal object ShizukuRecoveryPolicy {
    const val MIN_RETRY_INTERVAL_MS = 90_000L

    fun shouldRecover(
        adbVerified: Boolean,
        autoEnabled: Boolean,
        manuallyStopped: Boolean,
        busy: Boolean,
        binderReady: Boolean,
        elapsedMs: Long,
    ): Boolean = adbVerified && autoEnabled && !manuallyStopped && !busy &&
        !binderReady && elapsedMs >= MIN_RETRY_INTERVAL_MS
}

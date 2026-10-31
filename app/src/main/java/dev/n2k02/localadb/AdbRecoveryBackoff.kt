package dev.n2k02.localadb

/** Capped exponential reconnect delay, from VRec's AdbRecoveryBackoff. */
internal class AdbRecoveryBackoff(
    private val baseMs: Long = 60_000L,
    private val maxMs: Long = 30 * 60_000L,
) {
    var failures = 0
        private set
    private var allowedAt = 0L
    fun allowed(nowMs: Long) = nowMs >= allowedAt
    fun delayFor(count: Int): Long = if (count <= 0) 0L
        else (baseMs shl (count - 1).coerceAtMost(20)).coerceAtMost(maxMs)
    fun onFailure(nowMs: Long) {
        if (failures < Int.MAX_VALUE) failures++
        val delay = delayFor(failures)
        allowedAt = if (nowMs > Long.MAX_VALUE - delay) Long.MAX_VALUE else nowMs + delay
    }
    fun onSuccess() { failures = 0; allowedAt = 0L }
}

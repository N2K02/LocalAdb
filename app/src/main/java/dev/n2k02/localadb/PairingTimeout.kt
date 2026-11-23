package dev.n2k02.localadb

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Keeps Android from forgetting LocalAdb's ADB pairing after 7 days.
 *
 * Adapted from JemRec's PairingTimeout (Apache-2.0, https://github.com/jemcik/JemRec).
 * Android deletes an ADB key older than `adb_allowed_connection_time` (7 days
 * when unset) at the next write of its key list (a USB cable plugged in or out
 * is enough); Wireless debugging connections never refresh that age. Shizuku
 * keeps running, but after a reboot LocalAdb must be paired again. 0 is what
 * Developer options' "Disable adb authorization timeout" writes. The previous
 * value is recorded in shell-owned /data/local/tmp BEFORE the write and put
 * back, while it still reads 0, when the switch is turned off.
 */
internal object PairingTimeout {
    private const val TAG = "LocalAdbPairing"
    const val SETTING = "adb_allowed_connection_time"
    const val RECORD = "/data/local/tmp/localadb-pairing-timeout"

    enum class Outcome { KEPT, ALREADY_OFF, GIVEN_BACK, NOTHING_TO_GIVE_BACK, FAILED }

    val last = MutableStateFlow<Outcome?>(null)

    fun keepCommand(): String =
        "cur=\$(settings get global $SETTING); " +
            "if [ \"\$cur\" = 0 ]; then echo LOCALADB_PAIRING_ALREADY_OFF; else " +
            "{ [ -e $RECORD ] || printf %s \"\$cur\" > $RECORD; } && " +
            "settings put global $SETTING 0 && " +
            "[ \"\$(settings get global $SETTING)\" = 0 ] && echo LOCALADB_PAIRING_KEPT; fi"

    /** A recorded value that is not a plain number restores Android's default; never shell text. */
    fun giveBackCommand(): String =
        "if [ -e $RECORD ]; then v=\$(cat $RECORD); " +
            "case \"\$v\" in null) ;; ''|*[!0-9]*) v=null;; esac; " +
            "if [ \"\$(settings get global $SETTING)\" = 0 ]; then " +
            "if [ \"\$v\" = null ]; then settings delete global $SETTING >/dev/null; " +
            "else settings put global $SETTING \"\$v\"; fi; fi; " +
            "rm -f $RECORD && echo LOCALADB_PAIRING_GIVEN_BACK; " +
            "else echo LOCALADB_PAIRING_NOTHING; fi"

    fun parse(output: String?): Outcome {
        val lines = output.orEmpty().lineSequence().map(String::trim).toSet()
        return when {
            "LOCALADB_PAIRING_KEPT" in lines -> Outcome.KEPT
            "LOCALADB_PAIRING_ALREADY_OFF" in lines -> Outcome.ALREADY_OFF
            "LOCALADB_PAIRING_GIVEN_BACK" in lines -> Outcome.GIVEN_BACK
            "LOCALADB_PAIRING_NOTHING" in lines -> Outcome.NOTHING_TO_GIVE_BACK
            else -> Outcome.FAILED
        }
    }

    suspend fun apply(context: Context): Outcome {
        val keep = Resilience.keepPaired(context)
        val outcome = parse(AdbTransport.exec(
            if (keep) keepCommand() else giveBackCommand(), 10_000).getOrNull())
        last.value = outcome
        if (outcome == Outcome.FAILED) Log.w(TAG, "could not apply the ADB authorization timeout choice")
        else Log.i(TAG, "ADB authorization timeout: $outcome")
        return outcome
    }
}

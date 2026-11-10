package dev.n2k02.localadb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Opt-in automation API for the embedded Shizuku server.
 *
 * Explicitly target LocalAdb's package, not the independently installed
 * moe.shizuku.privileged.api manager. The token must never be logged.
 */
internal object ShizukuIntentAccess {
    const val ACTION_START = "moe.shizuku.privileged.api.START"
    const val ACTION_STOP = "moe.shizuku.privileged.api.STOP"
    const val EXTRA_AUTH = "auth"
    private const val PREFS = "shizuku_intent_access"
    private const val KEY_TOKEN = "token"
    private const val KEY_ENABLED = "enabled"
    private const val TOKEN_BYTES = 32

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (enabled && prefs.getString(KEY_TOKEN, null).isNullOrEmpty()) rotateToken(context)
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun token(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_TOKEN, null)?.takeIf { it.length == TOKEN_BYTES * 2 }
            ?: rotateToken(context)
    }

    fun rotateToken(context: Context): String {
        val bytes = ByteArray(TOKEN_BYTES)
        SecureRandom().nextBytes(bytes)
        val value = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_TOKEN, value).commit()
        return value
    }

    internal fun matches(expected: String, supplied: String?): Boolean {
        if (supplied == null || supplied.length != expected.length) return false
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8), supplied.toByteArray(Charsets.UTF_8)
        )
    }

    fun authorized(context: Context, supplied: String?): Boolean {
        if (!isEnabled(context)) return false
        val expected = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TOKEN, null) ?: return false
        return matches(expected, supplied)
    }
}

class ShizukuAutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != ShizukuIntentAccess.ACTION_START &&
            action != ShizukuIntentAccess.ACTION_STOP) return

        // Check before mutating any manager state; never include the key in logs.
        val supplied = intent.getStringExtra(ShizukuIntentAccess.EXTRA_AUTH)
        if (!ShizukuIntentAccess.authorized(context, supplied)) {
            Log.w("LocalAdbShizuku", "Ignored unauthorized automation intent")
            return
        }
        when (action) {
            ShizukuIntentAccess.ACTION_START ->
                NativeShizukuManager.start(context.applicationContext, explicit = true)
            ShizukuIntentAccess.ACTION_STOP ->
                NativeShizukuManager.stop(context.applicationContext)
        }
    }
}

package dev.n2k02.localadb

import android.annotation.SuppressLint
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.util.Log
import moe.shizuku.api.BinderContainer
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_TOKEN
import rikka.shizuku.ShizukuProvider

/** Manager endpoint completing the upstream ServiceStarter -> server handshake. */
class LocalShizukuProvider : ShizukuProvider() {
    override fun onCreate(): Boolean {
        disableAutomaticSuiInitialization()
        return super.onCreate()
    }

    @SuppressLint("RestrictedApi")
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != "sendUserService") return super.call(method, arg, extras)
        check(Binder.getCallingUid() == Process.SHELL_UID) { "Only shell can attach user services" }
        if (extras == null) return null
        return try {
            extras.classLoader = BinderContainer::class.java.classLoader
            val token = extras.getString(USER_SERVICE_ARG_TOKEN)?.takeIf { it.isNotBlank() }
                ?: return null
            @Suppress("DEPRECATION")
            val service = extras.getParcelable<BinderContainer>(EXTRA_BINDER)?.binder ?: return null
            if (!service.pingBinder() || !Shizuku.pingBinder()) return null
            // The server validates the token against its pending service record.
            Shizuku.attachUserService(service, Bundle().apply {
                putString(USER_SERVICE_ARG_TOKEN, token)
            })
            Bundle().apply { putParcelable(EXTRA_BINDER, BinderContainer(Shizuku.getBinder())) }
                .also { Log.i("LocalAdbShizuku", "User-service Binder attached") }
        } catch (failure: Exception) {
            Log.e("LocalAdbShizuku", "User-service Binder attachment failed", failure)
            null
        }
    }

    private companion object {
        const val EXTRA_BINDER = "moe.shizuku.privileged.api.intent.extra.BINDER"
    }
}
